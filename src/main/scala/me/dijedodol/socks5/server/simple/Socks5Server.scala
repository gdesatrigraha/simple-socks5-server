package me.dijedodol.socks5.server.simple

import java.lang.Boolean

import com.typesafe.scalalogging.LazyLogging
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{Channel, ChannelFuture, ChannelOption}
import me.dijedodol.socks5.server.simple.config.Socks5Config

class Socks5Server(val socks5Config: Socks5Config) extends LazyLogging {
  val acceptorEventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime.availableProcessors() * 1)
  val workerEventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime.availableProcessors() * 2)

  val serverBootstrap = new ServerBootstrap()
    .group(acceptorEventLoopGroup, workerEventLoopGroup)
    .channel(classOf[NioServerSocketChannel])
    .option(ChannelOption.SO_BACKLOG, new Integer(1024))
    .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE)
    .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
    .childOption(ChannelOption.AUTO_READ, Boolean.TRUE)
    .childHandler(new Socks5ClientInitializer(acceptorEventLoopGroup, workerEventLoopGroup, socks5Config))

  var serverChannel: Option[Channel] = None

  def start(): Channel = {
    if (serverChannel.isDefined) throw new IllegalStateException("already running")

    logger.info(s"starting simple socks5 server on ${socks5Config.bindAddress}")
    val ret = serverBootstrap.bind(socks5Config.bindAddress.getHostString, socks5Config.bindAddress.getPort).sync().channel()
    logger.info(s"simple socks5 server is now listening on ${socks5Config.bindAddress}")

    serverChannel = Some(ret)
    ret
  }

  def stop(): ChannelFuture = {
    logger.info("stopping simple socks5 server")
    val ret = serverChannel.map(f => f.close().sync()).getOrElse(throw new IllegalStateException("not running"))
    logger.info("simple socks5 server has been stopped")

    serverChannel = None
    ret
  }
}
