package com.shu.shuny.consumer;

import com.shu.shuny.model.SunnyResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;


public class NettyClientInvokeHandler extends SimpleChannelInboundHandler<SunnyResponse> {

    public NettyClientInvokeHandler() {
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, SunnyResponse response) throws Exception {
        //将Netty异步返回的结果存入阻塞队列,以便调用端同步获取
        RevokerResponseHolder.putResultValue(response);
    }


}
