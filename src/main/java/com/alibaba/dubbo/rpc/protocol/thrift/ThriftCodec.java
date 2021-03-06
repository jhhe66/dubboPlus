/**
 * File Created at 2011-12-05
 * $Id$
 *
 * Copyright 2008 Alibaba.com Croporation Limited.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Alibaba Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Alibaba.com.
 */
package com.alibaba.dubbo.rpc.protocol.thrift;


import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TIOStreamTransport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.thrift.io.RandomAccessByteArrayOutputStream;

/**
 * Thrift framed protocol codec.
 *
 * <pre>
 * |<-                                  message header                                  ->|<- message body ->|
 * +----------------+----------------------+------------------+---------------------------+------------------+
 * | magic (2 bytes)|message size (4 bytes)|head size(2 bytes)| version (1 byte) | header |   message body   |
 * +----------------+----------------------+------------------+---------------------------+------------------+
 * |<-                                               message size                                          ->|
 * </pre>
 *
 * <p>
 * <b>header fields in version 1</b>
 * <ol>
 *     <li>string - service name</li>
 *     <li>long   - dubbo request id</li>
 * </ol>
 * </p>
 *
 * @author <a href="mailto:gang.lvg@alibaba-inc.com">gang.lvg</a>
 */
public class ThriftCodec implements Codec2 {

    private static final AtomicInteger THRIFT_SEQ_ID = new AtomicInteger( 0 );

    private static final ConcurrentMap<String, Class<?>> cachedClass =
            new ConcurrentHashMap<String, Class<?>>();

    static final ConcurrentMap<Long, RequestData> cachedRequest =
            new ConcurrentHashMap<Long, RequestData>();

    public static final int MESSAGE_LENGTH_INDEX = 2;

    public static final int MESSAGE_HEADER_LENGTH_INDEX = 6;

    public static final int MESSAGE_SHORTEST_LENGTH = 10;

    public static final String NAME = "thrift";

    public static final String PARAMETER_CLASS_NAME_GENERATOR = "class.name.generator";

    public static final byte VERSION = (byte)1;

    public static final short MAGIC = (short) 0xdabc;
    
    //telnet解码器（原来是不支持的）
    private TelnetCodec TELNET_CODEC = new TelnetCodec();

    public void encode( Channel channel, ChannelBuffer buffer, Object message )
            throws IOException {

        if ( message instanceof Request ) {
            encodeRequest( channel, buffer, ( Request ) message );
        }
        else if ( message instanceof Response ) {
            encodeResponse( channel, buffer, ( Response ) message );
        }
        //新增telnet解码
        else if ( message instanceof String )
        {
        	TELNET_CODEC.encode(channel, buffer, message);
        } else {
            throw new UnsupportedOperationException(
                    new StringBuilder( 32 )
                            .append( "Thrift codec only support encode " )
                            .append( Request.class.getName() )
                            .append( " and " )
                            .append( Response.class.getName() )
                            .toString() );
        }

    }

    public Object decode( Channel channel, ChannelBuffer buffer ) throws IOException {

    	//原生thrift调用,从url提取isNative和接口方法（注意：只能配置一个service服务）
    	boolean isNative = Boolean.valueOf(channel.getUrl().getParameter("thrift_native"));
    	String serviceInterface = channel.getUrl().getServiceInterface();
    	
        int available = buffer.readableBytes();

        if ( available < MESSAGE_SHORTEST_LENGTH ) {

            //return DecodeResult.NEED_MORE_INPUT;
        	//新增telnet解码
        	return TELNET_CODEC.decode(channel, buffer);

        } else {

            TIOStreamTransport transport = new TIOStreamTransport( new ChannelBufferInputStream(buffer));

            TBinaryProtocol protocol = new TBinaryProtocol( transport );

            //原生thrift报文，没有这些header信息
            if (!isNative)
            {
            	   short magic;
                   int messageLength;

                   try{
                   	
                   	/**
       				 * |<-                                  message header                                  ->|<- message body ->|
       				 * +----------------+----------------------+------------------+---------------------------+------------------+
       				 * | magic (2 bytes)|message size (4 bytes)|head size(2 bytes)| version (1 byte) | header |   message body   |
       				 * +----------------+----------------------+------------------+---------------------------+------------------+
       				 * |<-                                               message size                                          ->|
                   	 */
                   	
//                       protocol.readI32(); // skip the first message length
                       byte[] bytes = new byte[4];
                       transport.read( bytes, 0, 4 );
                       magic = protocol.readI16();
                       messageLength = protocol.readI32();

                   } catch ( TException e ) {
                       throw new IOException( e.getMessage(), e );
                   }


                    if ( MAGIC != magic ) {
                           throw new IOException(
                                    new StringBuilder( 32 )
                                            .append( "Unknown magic code " )
                                             .append( magic )
                                             .toString() );
                   }

                   if ( available < messageLength ) { return  DecodeResult.NEED_MORE_INPUT; }
            }

            return decode( protocol ,isNative ,serviceInterface);

        }

    }

    private Object decode( TProtocol protocol ,boolean isNative ,String serviceInterface )
            throws IOException {

        // version
        String serviceName;
        long id;

        TMessage message;

        try {
        	
        	if (isNative)
        	{
        		serviceName = serviceInterface;
        		message = protocol.readMessageBegin();
        		id = message.seqid;//id不正确，会导致client报错
        	}
        	else
        	{
        		 protocol.readI16();//跳过header size
                 protocol.readByte();//跳过版本
                 serviceName = protocol.readString();
                 id = protocol.readI64();
                 message = protocol.readMessageBegin();
        	}
        	
        } catch ( TException e ) {
        	e.printStackTrace();
            throw new IOException( e.getMessage(), e );
        }

        if ( message.type == TMessageType.CALL ) {

            RpcInvocation result = new RpcInvocation();
            result.setAttachment(Constants.INTERFACE_KEY, serviceName );
            result.setMethodName( message.name );

            String argsClassName = ExtensionLoader.getExtensionLoader(ClassNameGenerator.class)
                    .getExtension(ThriftClassNameGenerator.NAME).generateArgsClassName( serviceName, message.name );

            if ( StringUtils.isEmpty( argsClassName ) ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION,
                                        "The specified interface name incorrect." );
            }

            Class clazz = cachedClass.get( argsClassName );

            if ( clazz == null ) {
                try {

                    clazz = ClassHelper.forNameWithThreadContextClassLoader( argsClassName );

                    cachedClass.putIfAbsent( argsClassName, clazz );

                } catch ( ClassNotFoundException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }
            }

            TBase args;

            try {
                args = ( TBase ) clazz.newInstance();
            } catch ( InstantiationException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            try{
                args.read( protocol );
                protocol.readMessageEnd();
            } catch ( TException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            List<Object> parameters = new ArrayList<Object>();
            List<Class<?>> parameterTypes =new ArrayList<Class<?>>();
            int index = 1;

            while ( true ) {

                TFieldIdEnum fieldIdEnum = args.fieldForId( index++ );

                if ( fieldIdEnum == null ) { break; }

                String fieldName = fieldIdEnum.getFieldName();

                String getMethodName = ThriftUtils.generateGetMethodName( fieldName );

                Method getMethod;

                try {
                    getMethod = clazz.getMethod( getMethodName );
                } catch ( NoSuchMethodException e ) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

                parameterTypes.add( getMethod.getReturnType() );
                try {
                    parameters.add( getMethod.invoke( args ) );
                } catch ( IllegalAccessException e ) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                } catch ( InvocationTargetException e ) {
                    throw new RpcException(
                            RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

            }

            result.setArguments( parameters.toArray() );
            result.setParameterTypes(parameterTypes.toArray(new Class[parameterTypes.size()]));

            Request request = new Request( id );
            request.setData( result );

            cachedRequest.putIfAbsent( id,
                                       RequestData.create( message.seqid, serviceName, message.name ) );

            return request;

        } else if ( message.type == TMessageType.EXCEPTION ) {

            TApplicationException exception;

            try {
                exception = TApplicationException.read( protocol );
                protocol.readMessageEnd();
            } catch ( TException e ) {
                throw new IOException( e.getMessage(), e );
            }

            RpcResult result = new RpcResult();

            result.setException( new RpcException( exception.getMessage() ) );

            Response response = new Response();

            response.setResult( result );

            response.setId( id );

            return response;

        } else if ( message.type == TMessageType.REPLY ) {

            String resultClassName = ExtensionLoader.getExtensionLoader( ClassNameGenerator.class )
                    .getExtension(ThriftClassNameGenerator.NAME).generateResultClassName( serviceName, message.name );

            if ( StringUtils.isEmpty( resultClassName ) ) {
                throw new IllegalArgumentException(
                        new StringBuilder( 32 )
                                .append( "Could not infer service result class name from service name " )
                                .append( serviceName )
                                .append( ", the service name you specified may not generated by thrift idl compiler" )
                                .toString() );
            }

            Class<?> clazz = cachedClass.get( resultClassName );

            if ( clazz == null ) {

                try {

                    clazz = ClassHelper.forNameWithThreadContextClassLoader( resultClassName );

                    cachedClass.putIfAbsent( resultClassName, clazz );

                } catch ( ClassNotFoundException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

            }

            TBase<?,? extends TFieldIdEnum> result;
            try {
                result = ( TBase<?,?> ) clazz.newInstance();
            } catch ( InstantiationException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            try {
                result.read( protocol );
                protocol.readMessageEnd();
            } catch ( TException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            Object realResult = null;

            int index = 0;

            while ( true ) {

                TFieldIdEnum fieldIdEnum = result.fieldForId( index++ );

                if ( fieldIdEnum == null ) { break ; }

                Field field;

                try {
                    field = clazz.getDeclaredField( fieldIdEnum.getFieldName() );
                    field.setAccessible( true );
                } catch ( NoSuchFieldException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

                try {
                    realResult = field.get( result );
                } catch ( IllegalAccessException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }

                if ( realResult != null ) { break ; }

            }

            Response response = new Response();

            response.setId( id );

            RpcResult rpcResult = new RpcResult();

            if ( realResult instanceof Throwable ) {
                rpcResult.setException( ( Throwable ) realResult );
            } else {
                rpcResult.setValue(realResult);
            }

            response.setResult( rpcResult );

            return response;

        } else {
            // Impossible
            throw new IOException(  );
        }

    }

    private void encodeRequest( Channel channel, ChannelBuffer buffer, Request request )
            throws IOException {

        RpcInvocation inv = ( RpcInvocation ) request.getData();

        int seqId = nextSeqId();

        String serviceName = inv.getAttachment(Constants.INTERFACE_KEY);

        if ( StringUtils.isEmpty( serviceName ) ) {
            throw new IllegalArgumentException(
                    new StringBuilder( 32 )
                            .append( "Could not find service name in attachment with key " )
                            .append(Constants.INTERFACE_KEY)
                            .toString() );
        }

        TMessage message = new TMessage(
                inv.getMethodName(),
                TMessageType.CALL,
                seqId );

        //获取thrift生成的client代码中特殊的类名字
        String methodArgs = ExtensionLoader.getExtensionLoader( ClassNameGenerator.class )
            .getExtension(channel.getUrl().getParameter(ThriftConstants.CLASS_NAME_GENERATOR_KEY, ThriftClassNameGenerator.NAME))//ThriftClassNameGenerator.NAME 这里改了 2016.6.1
            .generateArgsClassName(serviceName, inv.getMethodName());

        if ( StringUtils.isEmpty( methodArgs ) ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION,
                                    new StringBuilder(32).append(
                                            "Could not encode request, the specified interface may be incorrect." ).toString() );
        }

        /**
         * 把该类实例化成TBase后，执行相关的操作
         */
        Class<?> clazz = cachedClass.get( methodArgs );

        if ( clazz == null ) {

            try {

                clazz = ClassHelper.forNameWithThreadContextClassLoader( methodArgs );

                cachedClass.putIfAbsent( methodArgs, clazz );

            } catch ( ClassNotFoundException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        TBase args;

        try {
            args = (TBase) clazz.newInstance();
        } catch ( InstantiationException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        } catch ( IllegalAccessException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        //thrift 规定性动作
        for( int i = 0; i < inv.getArguments().length; i++ ) {

            Object obj = inv.getArguments()[i];

            if ( obj == null ) { continue; }

            TFieldIdEnum field = args.fieldForId( i + 1 );

            String setMethodName = ThriftUtils.generateSetMethodName( field.getFieldName() );

            Method method;

            try {
                method = clazz.getMethod( setMethodName, inv.getParameterTypes()[i] );
            } catch ( NoSuchMethodException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

            try {
                method.invoke( args, obj );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( InvocationTargetException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        RandomAccessByteArrayOutputStream bos = new RandomAccessByteArrayOutputStream( 1024 );

        TIOStreamTransport transport = new TIOStreamTransport( bos );

        TBinaryProtocol protocol = new TBinaryProtocol( transport );

        int headerLength, messageLength;

        /**
		 * |<-                                  message header                                  ->|<- message body ->|
		 * +----------------+----------------------+------------------+---------------------------+------------------+
		 * | magic (2 bytes)|message size (4 bytes)|head size(2 bytes)| version (1 byte) | header |   message body   |
		 * +----------------+----------------------+------------------+---------------------------+------------------+
		 * |<-                                               message size                                          ->|
         */
        
        byte[] bytes = new byte[4];
        try {
            // magic
            protocol.writeI16( MAGIC );
            // message length placeholder
            protocol.writeI32( Integer.MAX_VALUE );
            // message header length placeholder
            protocol.writeI16( Short.MAX_VALUE );
            // version
            protocol.writeByte( VERSION );
            // service name
            protocol.writeString( serviceName );
            // dubbo request id
            protocol.writeI64( request.getId() );
            protocol.getTransport().flush();
            // header size
            headerLength = bos.size();

            // message body
            protocol.writeMessageBegin( message );
            args.write( protocol );
            protocol.writeMessageEnd();
            protocol.getTransport().flush();
            int oldIndex = messageLength = bos.size();

            // fill in message length and header length
            try {
                TFramedTransport.encodeFrameSize( messageLength, bytes );
                bos.setWriteIndex( MESSAGE_LENGTH_INDEX );
                protocol.writeI32( messageLength );
                bos.setWriteIndex( MESSAGE_HEADER_LENGTH_INDEX );
                protocol.writeI16( ( short )( 0xffff & headerLength ) );
            } finally {
                bos.setWriteIndex( oldIndex );
            }

        } catch ( TException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        buffer.writeBytes(bytes);
        buffer.writeBytes(bos.toByteArray());

    }

    private void encodeResponse( Channel channel, ChannelBuffer buffer, Response response )
            throws IOException {

    	boolean isNative = Boolean.valueOf(channel.getUrl().getParameter("thrift_native"));
    	
        RpcResult result = ( RpcResult ) response.getResult();

        RequestData rd = cachedRequest.get( response.getId() );

        //获得thrift生成client的代码中的类名
        String resultClassName = ExtensionLoader.getExtensionLoader( ClassNameGenerator.class ).getExtension(
                    channel.getUrl().getParameter(ThriftConstants.CLASS_NAME_GENERATOR_KEY, ThriftClassNameGenerator.NAME))
                    .generateResultClassName(rd.serviceName, rd.methodName);

        if ( StringUtils.isEmpty( resultClassName ) ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION,
                                    new StringBuilder( 32 ).append(
                                            "Could not encode response, the specified interface may be incorrect." ).toString() );
        }

        Class clazz = cachedClass.get( resultClassName );

        if ( clazz == null ) {

            try {
                clazz = ClassHelper.forNameWithThreadContextClassLoader(resultClassName);
                cachedClass.putIfAbsent( resultClassName, clazz );
            } catch ( ClassNotFoundException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        TBase resultObj;

        try {
            resultObj = ( TBase ) clazz.newInstance();
        } catch ( InstantiationException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        } catch ( IllegalAccessException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        TApplicationException applicationException = null;
        TMessage message;

        if ( result.hasException() ) {
            Throwable throwable = result.getException();
            int index = 1;
            boolean found = false;
            while ( true ) {
                TFieldIdEnum fieldIdEnum = resultObj.fieldForId( index++ );
                if ( fieldIdEnum == null ) { break; }
                String fieldName = fieldIdEnum.getFieldName();
                String getMethodName = ThriftUtils.generateGetMethodName( fieldName );
                String setMethodName = ThriftUtils.generateSetMethodName( fieldName );
                Method getMethod;
                Method setMethod;
                try {
                    getMethod = clazz.getMethod( getMethodName );
                    if ( getMethod.getReturnType().equals( throwable.getClass() ) ) {
                        found = true;
                        setMethod = clazz.getMethod( setMethodName, throwable.getClass() );
                        setMethod.invoke( resultObj, throwable );
                    }
                } catch ( NoSuchMethodException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                } catch ( InvocationTargetException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                } catch ( IllegalAccessException e ) {
                    throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
                }
            }

            if ( !found ) {
                applicationException = new TApplicationException( throwable.getMessage() );
            }

        } else {//thrift规定动作
            Object realResult = result.getResult();
            // result field id is 0
            String fieldName = resultObj.fieldForId( 0 ).getFieldName();
            String setMethodName = ThriftUtils.generateSetMethodName( fieldName );
            String getMethodName = ThriftUtils.generateGetMethodName( fieldName );
            Method getMethod;
            Method setMethod;
            try {
                getMethod = clazz.getMethod( getMethodName );
                setMethod = clazz.getMethod( setMethodName, getMethod.getReturnType() );
                setMethod.invoke( resultObj, realResult );
            } catch ( NoSuchMethodException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( InvocationTargetException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            } catch ( IllegalAccessException e ) {
                throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
            }

        }

        if ( applicationException != null ) {
            message = new TMessage( rd.methodName, TMessageType.EXCEPTION, rd.id );
        } else {
            message = new TMessage( rd.methodName, TMessageType.REPLY, rd.id );
        }

        RandomAccessByteArrayOutputStream bos = new RandomAccessByteArrayOutputStream( 1024 );

        TIOStreamTransport transport = new TIOStreamTransport( bos );

        TBinaryProtocol protocol = new TBinaryProtocol( transport );

        int messageLength;
        int headerLength;

        byte[] bytes = new byte[4];
        try {
        	
        	if (!isNative)//原生屏蔽掉
        	{
                // magic
                protocol.writeI16( MAGIC );
                // message length
                protocol.writeI32( Integer.MAX_VALUE );
                // message header length
                protocol.writeI16( Short.MAX_VALUE );
                // version
                protocol.writeByte( VERSION );
                // service name
                protocol.writeString( rd.serviceName );
                // id
                protocol.writeI64( response.getId() );
                protocol.getTransport().flush();
        	}

            headerLength = bos.size();
            // message
            protocol.writeMessageBegin( message );
            switch ( message.type ) {
                case TMessageType.EXCEPTION:
                    applicationException.write( protocol );
                    break;
                case TMessageType.REPLY:
                    resultObj.write( protocol );
                    break;
            }
            protocol.writeMessageEnd();
            protocol.getTransport().flush();
            int oldIndex = messageLength = bos.size();

            try{
            	
            	if (!isNative)//原生屏蔽掉
            	{
            		 TFramedTransport.encodeFrameSize( messageLength, bytes );
                     bos.setWriteIndex( MESSAGE_LENGTH_INDEX );
                     protocol.writeI32( messageLength );
                     bos.setWriteIndex( MESSAGE_HEADER_LENGTH_INDEX );
                     protocol.writeI16( ( short ) ( 0xffff & headerLength ) );
            	}
               
            } finally {
                bos.setWriteIndex( oldIndex );
            }

        } catch ( TException e ) {
            throw new RpcException( RpcException.SERIALIZATION_EXCEPTION, e.getMessage(), e );
        }

        if (!isNative)//原生屏蔽掉
    	{
        	buffer.writeBytes(bytes);
    	}
        buffer.writeBytes(bos.toByteArray());
    }

    private static int nextSeqId() {
        return THRIFT_SEQ_ID.incrementAndGet();
    }

    // just for test
    static int getSeqId() {
        return THRIFT_SEQ_ID.get();
    }

    static class RequestData {
        int id;
        String serviceName;
        String methodName;

        static RequestData create( int id, String sn, String mn ) {
            RequestData result = new RequestData();
            result.id = id;
            result.serviceName = sn;
            result.methodName = mn;
            return result;
        }

    }

}
