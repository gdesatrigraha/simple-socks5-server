package me.dijedodol.socks5.server.simple

import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelInitializer, EventLoopGroup}
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import me.dijedodol.socks5.server.simple.config.Socks5Config

class Socks5ClientInitializer(val acceptorEventLoopGroup: EventLoopGroup, val workerEventLoopGroup: EventLoopGroup, val socks5Config: Socks5Config) extends ChannelInitializer[SocketChannel] {
  override def initChannel(ch: SocketChannel): Unit = {
    ch.config.setPerformancePreferences(0, 2, 1)

    ch.pipeline()
      .addLast(new SocksPortUnificationServerHandler())
      .addLast(new Socks5ClientInboundHandler(acceptorEventLoopGroup, workerEventLoopGroup, socks5Config))
  }
}
