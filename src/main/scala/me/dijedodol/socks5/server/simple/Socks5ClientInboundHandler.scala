package me.dijedodol.socks5.server.simple

import java.net._

import com.typesafe.scalalogging.LazyLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.handler.codec.socksx.v5._
import me.dijedodol.socks5.server.simple.config.Socks5Config
import me.dijedodol.socks5.server.simple.handler.TunnelingChannelInboundHandler

import scala.collection.JavaConverters

class Socks5ClientInboundHandler(val clientBootstrap: Bootstrap, val socks5Config: Socks5Config) extends ChannelInboundHandlerAdapter with LazyLogging {

  var state: Int = 0

  def socks5HandleState0(ctx: ChannelHandlerContext, msg: Any) = {
    val socks5InitialRequest = msg.asInstanceOf[Socks5InitialRequest]
    val authMethods = JavaConverters.collectionAsScalaIterable(socks5InitialRequest.authMethods())

    val selectedAuthMethod = if (socks5Config.authConfig.isAuthenticationRequired()) authMethods.filter(f => Socks5AuthMethod.PASSWORD == f).headOption.getOrElse(Socks5AuthMethod.UNACCEPTED)
    else if (authMethods.isEmpty) Socks5AuthMethod.NO_AUTH
    else authMethods.filter(f => Socks5AuthMethod.NO_AUTH == f).headOption.getOrElse(Socks5AuthMethod.UNACCEPTED)

    selectedAuthMethod match {
      case Socks5AuthMethod.UNACCEPTED => {
        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener((f: ChannelFuture) => f.channel().close())
      }
      case Socks5AuthMethod.PASSWORD => {
        state = 1

        ctx.pipeline().remove(classOf[Socks5InitialRequestDecoder])
        ctx.pipeline().addBefore(ctx.name(), null, new Socks5PasswordAuthRequestDecoder())

        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD))
      }
      case Socks5AuthMethod.NO_AUTH => {
        state = 2

        ctx.pipeline().remove(classOf[Socks5InitialRequestDecoder])
        ctx.pipeline().addBefore(ctx.name(), null, new Socks5CommandRequestDecoder())

        ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
      }
      case _ => ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED)).addListener((f: ChannelFuture) => f.channel().close())
    }
  }

  def socks5HandleState1(ctx: ChannelHandlerContext, msg: Any) = {
    val socks5PasswordAuthRequest = msg.asInstanceOf[Socks5PasswordAuthRequest]

    if (socks5Config.authConfig.isAuthenticationMatch(Option(socks5PasswordAuthRequest.username()), Option(socks5PasswordAuthRequest.password()))) {
      state = 2

      ctx.pipeline().remove(classOf[Socks5PasswordAuthRequestDecoder])
      ctx.pipeline().addBefore(ctx.name(), null, new Socks5CommandRequestDecoder())

      ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS))
    } else {
      ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener((f: ChannelFuture) => f.channel().close())
    }
  }

  def socks5HandleState2(ctx: ChannelHandlerContext, msg: Any) = {
    state = Int.MaxValue

    val clientChannel = ctx.channel();
    val socks5CommandRequest = msg.asInstanceOf[Socks5CommandRequest]

    socks5CommandRequest.`type`() match {
      case Socks5CommandType.CONNECT => {
        clientChannel.config().setAutoRead(false)

        clientBootstrap.connect(socks5CommandRequest.dstAddr(), socks5CommandRequest.dstPort()).addListener((f: ChannelFuture) => {
          val peerChannel = f.channel();

          if (!peerChannel.isActive) {
            logger.warn(s"peerChannel is unable to establish connection to: ${socks5CommandRequest.dstAddr()}:${socks5CommandRequest.dstPort()} reason: ${f.cause()}")

            peerChannel.close()
            clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(f.cause() match {
              case e: ConnectException => Socks5CommandStatus.CONNECTION_REFUSED
              case e: NoRouteToHostException => Socks5CommandStatus.HOST_UNREACHABLE
              case _ => Socks5CommandStatus.FAILURE
            }, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
          } else if (!peerChannel.localAddress().isInstanceOf[InetSocketAddress]) {
            logger.warn(s"unexpected localAddress: ${Option(peerChannel.localAddress())} className: ${Option(peerChannel.localAddress()).map(f => f.getClass.getName)} on peerChannel: ${peerChannel}")

            peerChannel.close()
            clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
          } else {
            val localAddress = peerChannel.localAddress().asInstanceOf[InetSocketAddress]

            if ((localAddress.getAddress match {
              case f: Inet4Address => Some(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4, f.getHostAddress, localAddress.getPort))
              case f: Inet6Address => Some(new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv6, f.getHostAddress, localAddress.getPort))
              case _ => None
            }).map(f => {
              clientChannel.writeAndFlush(f).addListener((f: ChannelFuture) => {
                clientChannel.pipeline().remove(classOf[Socks5CommandRequestDecoder])
                clientChannel.pipeline().remove(classOf[Socks5ClientInboundHandler])
                clientChannel.pipeline().remove(classOf[Socks5ServerEncoder])

                peerChannel.pipeline().addLast(new TunnelingChannelInboundHandler(clientChannel))
                clientChannel.pipeline().addLast(new TunnelingChannelInboundHandler(peerChannel))

                peerChannel.config().setAutoRead(true)
                clientChannel.config().setAutoRead(true)
              })
            }).isEmpty) {
              logger.warn(s"peerChannel unknown localAddress IP className: ${Option(localAddress.getAddress).map(f => f.getClass.getName)} on peerChannel: ${peerChannel}")

              peerChannel.close()
              clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
            }
          }
        })
      }
      case _ => clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
    }
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    (state, msg) match {
      case (0, f: Socks5InitialRequest) => socks5HandleState0(ctx, f)
      case (1, f: Socks5PasswordAuthRequest) => socks5HandleState1(ctx, f)
      case (2, f: Socks5CommandRequest) => socks5HandleState2(ctx, f)
      case _ => {
        logger.info(s"unexpected msg: $msg state: $state on channel: ${ctx.channel()}")
        ctx.close()
      }
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    logger.error("unexpected exception on channel: ${ctx.channel()}", cause)
    ctx.close()
  }
}
