package com.im.server.codec;

import com.im.server.common.ProtocolConstants;
import com.im.server.entity.IMProtocol;
import com.im.server.factory.ProtocolFactory;
import com.im.server.processor.EncryptProcessor;
import com.im.server.processor.SerializeProcessor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// 作为自定义协议的decode与encode
@Slf4j
public class IMProtocolCodec extends ByteToMessageCodec<IMProtocol>  {

    private IMProtocol imMessage;

    private Integer badMessageCount=0;

    private final HashMap<Byte, SerializeProcessor> serializeProcessorMap;

    private final HashMap<Byte, EncryptProcessor> encryptProcessorMap;

    private AtomicBoolean resetRequest= new AtomicBoolean(false);

    private State currentState=State.READ_MAGIC;

    private enum State{
        READ_MAGIC,
        READ_VERSION,
        READ_SERIALIZE_ALGORITHM,
        READ_ENCRYPT,
        READ_COMMAND,
        READ_STATUS,
        READ_DATA_TYPE,
        READ_BODY,
        BAD_MESSAGE
    }

    public IMProtocolCodec(HashMap<Byte,EncryptProcessor> encryptProcessorMap,HashMap<Byte,SerializeProcessor> serializeProcessorMap){
        this.encryptProcessorMap = encryptProcessorMap;
        this.serializeProcessorMap = serializeProcessorMap;
    }


    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, IMProtocol imProtocol, ByteBuf byteBuf) throws Exception {
        byteBuf.writeByte(imProtocol.getMagic())
                .writeByte(imProtocol.getVersion())
                .writeByte(imProtocol.getSerializeAlgorithm())
                .writeByte(imProtocol.getEncrypt())
                .writeByte(imProtocol.getCommand())
                .writeByte(imProtocol.getStatus())
                .writeByte(imProtocol.getDataType())
                .writeInt(imProtocol.getLength());
        byte[] body;
//        Serialize and Encrypt
        SerializeProcessor serializeProcessor = serializeProcessorMap.get(imProtocol.getSerializeAlgorithm());
        switch (imProtocol.getDataType()){
            case ProtocolConstants.OBJECT_TYPE:{
                body = serializeProcessor.serialize(imProtocol.getBody());
                break;
            }
            case ProtocolConstants.INTEGER_TYPE:
            case ProtocolConstants.STRING_TYPE: {
                body=imProtocol.getBody().toString().getBytes();
                break;
            }
            default:{
                log.error("Data type is not correct");
                throw new ClassNotFoundException();
            }
        }

        if(imProtocol.getEncrypt()!= ProtocolConstants.NO_ENCRYPT){
            EncryptProcessor encryptProcessor = encryptProcessorMap.get(imProtocol.getEncrypt());
            byteBuf.writeBytes(encryptProcessor.encrypt(body));
        }

        channelHandlerContext.writeAndFlush(byteBuf);
    }

    @Override

    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
//        Thread.sleep(500);
//        log.info("["+channelHandlerContext.channel()+"]Remains:"+byteBuf.readableBytes());
        byte temp;
        switch (currentState){
           case READ_MAGIC:{
               if ((temp=byteBuf.readByte()) != ProtocolConstants.MAGIC) {
                   log.error("Magic is not correct:["+String.format("0x%02x", temp)+"]");
                   currentState=State.BAD_MESSAGE;
                   return;
               }
               currentState=State.READ_VERSION;
           }
           case READ_VERSION:{
               if ((temp=byteBuf.readByte()) != ProtocolConstants.VERSION) {
                   log.error("Version is not correct"+String.format("0x%02x", temp)+"]");
                   IMProtocol<String> versionIsNotCorrect = ProtocolFactory.createProtocol("Version is not correct"
                           , ProtocolConstants.NO_ENCRYPT, ProtocolConstants.JSON_ALGORITHM
                           , ProtocolConstants.CHECK_COMMAND, ProtocolConstants.FAIL_STATUS);
                   versionIsNotCorrect.setDataType(ProtocolConstants.STRING_TYPE);
                   channelHandlerContext.writeAndFlush(versionIsNotCorrect);
                   currentState=State.BAD_MESSAGE;
                   return;
               }
               currentState=State.READ_SERIALIZE_ALGORITHM;
           }

            case READ_SERIALIZE_ALGORITHM:{
                temp=byteBuf.readByte();
                if(temp!=ProtocolConstants.JSON_ALGORITHM && temp!=ProtocolConstants.PROTOBUF_ALGORITHM){
                    log.error("Serialize algorithm is not correct"+String.format("0x%02x", temp)+"]");
                    currentState=State.BAD_MESSAGE;
                    return;
                }
                imMessage.setSerializeAlgorithm(temp);
                currentState=State.READ_ENCRYPT;
            }

            case READ_ENCRYPT:{
                temp=byteBuf.readByte();
                if(temp!=ProtocolConstants.AES_ENCRYPT&&temp!=ProtocolConstants.DES_ENCRYPT&&temp!=ProtocolConstants.NO_ENCRYPT){
                    log.error("Encrypt algorithm is not correct"+String.format("0x%02x", temp)+"]");
                    currentState=State.BAD_MESSAGE;
                    return;
                }
                imMessage.setEncrypt(temp);
                currentState=State.READ_COMMAND;
            }

            case READ_COMMAND:{
                imMessage.setCommand(byteBuf.readByte());
                currentState=State.READ_STATUS;
            }

            case READ_STATUS:{
                imMessage.setStatus(byteBuf.readByte());
                currentState=State.READ_DATA_TYPE;
            }

            case READ_DATA_TYPE:{
                temp=byteBuf.readByte();
                switch (temp){
                    case ProtocolConstants.OBJECT_TYPE:{
//                        TODO ?
                        break;
                    }
                    case ProtocolConstants.STRING_TYPE:{
                        log.info("/");
                        break;
                    }
                    case ProtocolConstants.INTEGER_TYPE:{
                        byteBuf.skipBytes(8);
                        currentState=State.READ_MAGIC;
                        return;
                    }
                    case ProtocolConstants.BYTE_TYPE:
                    case ProtocolConstants.BOOLEAN_TYPE:{
                        byteBuf.skipBytes(5);
                        currentState=State.READ_MAGIC;
                        return;
                    }

                    default:{
                        log.error("Data type is not correct"+String.format("0x%02x", temp)+"]");
                        currentState=State.BAD_MESSAGE;
                        return;
                    }
                }
                currentState=State.READ_BODY;
            }

            case READ_BODY:{
                int length = byteBuf.readInt();
//                如果存在网络延迟，会导致半包，需要等待
                if(byteBuf.readableBytes()<length){
                    byteBuf.resetReaderIndex();
                    currentState=State.READ_MAGIC;
                    return;
                }
                byte[] body = new byte[length];
                byteBuf.readBytes(body);
                EncryptProcessor encryptProcessor = encryptProcessorMap.get(imMessage.getEncrypt());
                byte[] decrypt = encryptProcessor.decrypt(body);

                break;
            }

            case BAD_MESSAGE:{
                byteBuf.skipBytes(byteBuf.readableBytes());
                badMessageCount+=1;
                currentState=State.READ_MAGIC;
                if (badMessageCount>5){
                    channelHandlerContext.close();
                }
                break;
            }
       }
    }
    public IMProtocol buildResponse(){
        return new IMProtocol();
    }

}
