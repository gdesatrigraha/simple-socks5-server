package me.dijedodol.socks5.server.simple.handler

import com.typesafe.scalalogging.LazyLogging
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}

class TunnelingChannelInboundHandler(val peerChannel: Channel) extends ChannelInboundHandlerAdapter with LazyLogging {

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    peerChannel.writeAndFlush(msg)
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    logger.info(s"inactive channel: ${ctx.channel()}")
    peerChannel.close()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    logger.error(s"unexpected exception on channel: ${ctx.channel()}", cause)
    ctx.close()
    peerChannel.close()
  }
}
