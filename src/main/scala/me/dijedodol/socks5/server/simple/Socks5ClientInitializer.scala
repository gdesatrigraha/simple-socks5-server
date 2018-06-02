package me.dijedodol.socks5.server.simple

import java.lang.Boolean

import io.netty.bootstrap.Bootstrap
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{ChannelInitializer, ChannelOption, EventLoopGroup}
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler
import me.dijedodol.socks5.server.simple.config.Socks5Config

class Socks5ClientInitializer(val acceptorEventLoopGroup: EventLoopGroup, val workerEventLoopGroup: EventLoopGroup, val socks5Config: Socks5Config) extends ChannelInitializer[SocketChannel] {

  val clientBootstrap = createClientBootstrap

  override def initChannel(ch: SocketChannel): Unit = {
    ch.config.setPerformancePreferences(0, 2, 1)

    ch.pipeline()
      .addLast(new SocksPortUnificationServerHandler())
      .addLast(new Socks5ClientInboundHandler(clientBootstrap, socks5Config))
  }

  def createClientBootstrap(): Bootstrap = {
    new Bootstrap()
      .group(workerEventLoopGroup)
      .channel(classOf[NioSocketChannel])
      .option(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)
      .option(ChannelOption.TCP_NODELAY, Boolean.TRUE)
      .option(ChannelOption.AUTO_READ, Boolean.FALSE)
      .handler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          ch.config.setPerformancePreferences(0, 2, 1)
        }
      })
  }

}
