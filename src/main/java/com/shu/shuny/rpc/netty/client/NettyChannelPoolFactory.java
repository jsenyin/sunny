package com.shu.shuny.rpc.netty.client;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.shu.shuny.common.enumeration.SerializeTypeEnum;
import com.shu.shuny.common.exception.BizException;
import com.shu.shuny.common.util.PropertyConfigerHelper;
import com.shu.shuny.model.ProviderServiceMeta;
import com.shu.shuny.model.SunnyRequest;
import com.shu.shuny.serialization.NettyDecoderHandler;
import com.shu.shuny.serialization.NettyEncoderHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

/**
 * @Author:shucq
 * @Description:
 * @Date 2019/10/2 20:48
 */
@NoArgsConstructor(access= AccessLevel.PRIVATE)
public class NettyChannelPoolFactory {

    private static final Logger logger = LoggerFactory.getLogger(NettyChannelPoolFactory.class);

    private static final NettyChannelPoolFactory channelPoolFactory = new NettyChannelPoolFactory();

    //Key为服务提供者地址,value为Netty Channel阻塞队列
    private static final Map<InetSocketAddress, ArrayBlockingQueue<Channel>> channelPoolMap = Maps.newConcurrentMap();
    //初始化Netty Channel阻塞队列的长度,该值为可配置信息
    private static final int CHANNEL_CONNECT_SIZE = PropertyConfigerHelper.getChannelConnectSize();
    //初始化序列化协议类型,该值为可配置信息
    private static final SerializeTypeEnum serializeType = PropertyConfigerHelper.getSerializeType();
    //服务提供者列表
    private List<ProviderServiceMeta> serviceMetaDataList = Lists.newArrayList();





    /**
     * 初始化Netty channel 连接队列Map
     *
     * @param providerMap
     */
    public void initChannelPoolFactory(Map<String, List<ProviderServiceMeta>> providerMap) {
        //将服务提供者信息存入serviceMetaDataList列表
        Collection<List<ProviderServiceMeta>> collectionServiceMetaDataList = providerMap.values();
        for (List<ProviderServiceMeta> serviceMetaDataModels : collectionServiceMetaDataList) {
            if (CollectionUtils.isEmpty(serviceMetaDataModels)) {
                continue;
            }
            serviceMetaDataList.addAll(serviceMetaDataModels);
        }

        //获取服务提供者地址列表
        Set<InetSocketAddress> socketAddressSet = Sets.newHashSet();
        for (ProviderServiceMeta serviceMetaData : serviceMetaDataList) {
            String serviceIp = serviceMetaData.getServerIp();
            int servicePort = serviceMetaData.getServerPort();

            InetSocketAddress socketAddress = new InetSocketAddress(serviceIp, servicePort);
            socketAddressSet.add(socketAddress);
        }

        //根据服务提供者地址列表初始化Channel阻塞队列,并以地址为Key,地址对应的Channel阻塞队列为value,存入channelPoolMap
        for (InetSocketAddress socketAddress : socketAddressSet) {
            try {
                int realChannelConnectSize = 0;
                while (realChannelConnectSize < CHANNEL_CONNECT_SIZE) {
                    Channel channel = null;
                    while (channel == null) {
                        //若channel不存在,则注册新的Netty Channel
                        channel = registerChannel(socketAddress);
                    }
                    //计数器,初始化的时候存入阻塞队列的Netty Channel个数不超过channelConnectSize
                    realChannelConnectSize++;

                    //将新注册的Netty Channel存入阻塞队列channelArrayBlockingQueue
                    // 并将阻塞队列channelArrayBlockingQueue作为value存入channelPoolMap
                    ArrayBlockingQueue<Channel> channelArrayBlockingQueue = channelPoolMap.get(socketAddress);
                    if (channelArrayBlockingQueue == null) {
                        channelArrayBlockingQueue = new ArrayBlockingQueue<>(CHANNEL_CONNECT_SIZE);
                        channelPoolMap.put(socketAddress, channelArrayBlockingQueue);
                    }
                    channelArrayBlockingQueue.offer(channel);
                }
            } catch (Exception e) {
                throw new BizException(e);
            }
        }
    }


    /**
     * 根据服务提供者地址获取对应的Netty Channel阻塞队列
     *
     * @param socketAddress
     * @return
     */
    public ArrayBlockingQueue<Channel> acquire(InetSocketAddress socketAddress) {
        return channelPoolMap.get(socketAddress);
    }


    /**
     * Channel使用完毕之后,回收到阻塞队列arrayBlockingQueue
     *
     * @param arrayBlockingQueue
     * @param channel
     * @param inetSocketAddress
     */
    public void release(ArrayBlockingQueue<Channel> arrayBlockingQueue, Channel channel, InetSocketAddress inetSocketAddress) {
        if (arrayBlockingQueue == null) {
            return;
        }

        //回收之前先检查channel是否可用,不可用的话,重新注册一个,放入阻塞队列
        if (channel == null || !channel.isActive() || !channel.isOpen() || !channel.isWritable()) {
            if (channel != null) {
                channel.deregister().syncUninterruptibly().awaitUninterruptibly();
                channel.closeFuture().syncUninterruptibly().awaitUninterruptibly();
            }
            Channel newChannel = null;
            while (newChannel == null) {
                logger.debug("---------register new Channel-------------");
                newChannel = registerChannel(inetSocketAddress);
            }
            arrayBlockingQueue.offer(newChannel);
            return;
        }
        arrayBlockingQueue.offer(channel);
    }


    /**
     * 为服务提供者地址socketAddress注册新的Channel
     *
     * @param socketAddress
     * @return
     */
    public Channel registerChannel(InetSocketAddress socketAddress) {
        try {
            EventLoopGroup group = new NioEventLoopGroup(10);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.remoteAddress(socketAddress);

            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast("clientLengthFieldBasedFrameDecoder",new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4));
                        ch.pipeline().addLast(new NettyDecoderHandler(SunnyRequest.class, serializeType));
                        ch.pipeline().addLast(new LengthFieldPrepender(4));
                        //注册编码器NettyEncoderHandler
                        ch.pipeline().addLast(new NettyEncoderHandler(serializeType));
                        //注册服务端业务逻辑处理器NettyServerInvokeHandler
                        ch.pipeline().addLast(new NettyClientInvokeHandler());
                    }
                });

            ChannelFuture channelFuture = bootstrap.connect().sync();
            final Channel newChannel = channelFuture.channel();
            final CountDownLatch connectedLatch = new CountDownLatch(1);

            final List<Boolean> isSuccessHolder = Lists.newArrayListWithCapacity(1);
            //监听Channel是否建立成功
            channelFuture.addListener((ChannelFutureListener) future -> {
                //若Channel建立成功,保存建立成功的标记
                if (future.isSuccess()) {
                    isSuccessHolder.add(Boolean.TRUE);
                } else {
                    //若Channel建立失败,保存建立失败的标记
                    logger.warn("register Channel error (msg={})", future.cause());
                    isSuccessHolder.add(Boolean.FALSE);
                }
                connectedLatch.countDown();
            });

            connectedLatch.await();
            //如果Channel建立成功,返回新建的Channel
            if (isSuccessHolder.get(0)) {
                return newChannel;
            }
        } catch (Exception e) {
            throw new BizException(e);
        }
        return null;
    }


    public static NettyChannelPoolFactory channelPoolFactoryInstance() {
        return channelPoolFactory;
    }


}
