package me.dijedodol.socks5.server.simple

import java.lang.Boolean
import java.net.{Inet4Address, Inet6Address, InetSocketAddress}

import com.typesafe.scalalogging.LazyLogging
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.socksx.v5._
import me.dijedodol.socks5.server.simple.config.Socks5Config

import scala.collection.JavaConverters

class Socks5ClientInboundHandler(val acceptorEventLoopGroup: EventLoopGroup, val workerEventLoopGroup: EventLoopGroup, val socks5Config: Socks5Config) extends ChannelInboundHandlerAdapter with LazyLogging {

  var state: Int = 0

  def handleState0(ctx: ChannelHandlerContext, msg: Any) = {
    if (msg.isInstanceOf[Socks5InitialRequest]) {
      val socks5InitialRequest = msg.asInstanceOf[Socks5InitialRequest]
      val authMethods = JavaConverters.collectionAsScalaIterable(socks5InitialRequest.authMethods())

      val tmp = socks5Config.authConfig.isAuthenticationRequired()

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
    } else ctx.close()
  }

  def handleState1(ctx: ChannelHandlerContext, msg: Any) = {
    if (msg.isInstanceOf[Socks5PasswordAuthRequest]) {
      val socks5PasswordAuthRequest = msg.asInstanceOf[Socks5PasswordAuthRequest]

      if (socks5Config.authConfig.isAuthenticationMatch(Option(socks5PasswordAuthRequest.username()), Option(socks5PasswordAuthRequest.password()))) {
        state = 2

        ctx.pipeline().remove(classOf[Socks5PasswordAuthRequestDecoder])
        ctx.pipeline().addBefore(ctx.name(), null, new Socks5CommandRequestDecoder())

        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS))
      } else {
        ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE)).addListener((f: ChannelFuture) => f.channel().close())
      }
    } else ctx.close()
  }

  def handleState2(ctx: ChannelHandlerContext, msg: Any) = {
    if (msg.isInstanceOf[Socks5CommandRequest]) {
      state = Int.MaxValue

      val clientChannel = ctx.channel();
      val socks5CommandRequest = msg.asInstanceOf[Socks5CommandRequest]

      socks5CommandRequest.`type`() match {
        case Socks5CommandType.CONNECT => {
          clientChannel.config().setAutoRead(false)

          val bootstrap = new Bootstrap()
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

          bootstrap.connect(socks5CommandRequest.dstAddr(), socks5CommandRequest.dstPort()).addListener((f: ChannelFuture) => {
            val peerChannel = f.channel();

            if (!peerChannel.localAddress().isInstanceOf[InetSocketAddress]) {
              logger.warn(s"unexpected localAddress: ${Option(peerChannel.localAddress())} class: ${Option(peerChannel.localAddress()).map(f => f.getClass)}")

              peerChannel.close()
              clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
            }

            logger.info(s"peerChannel connected to remoteAddress: ${socks5CommandRequest.dstAddr()}:${socks5CommandRequest.dstPort()}")
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

                peerChannel.pipeline().addLast(new ChannelInboundHandlerAdapter {
                  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
                    clientChannel.writeAndFlush(msg)
                  }

                  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                    logger.info(s"peerChannel inactive on remoteAddress: ${socks5CommandRequest.dstAddr()}:${socks5CommandRequest.dstPort()}")
                    clientChannel.close()
                  }

                  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
                    logger.error("unexpected exception on peerChannel", cause)
                    ctx.close()
                    clientChannel.close()
                  }
                })

                clientChannel.pipeline().addLast(new ChannelInboundHandlerAdapter {
                  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
                    peerChannel.writeAndFlush(msg)
                  }

                  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
                    logger.info(s"clientChannel inactive with peerChannel's remoteAddress: ${socks5CommandRequest.dstAddr()}:${socks5CommandRequest.dstPort()}")
                    peerChannel.close()
                  }

                  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
                    logger.error("unexpected exception on clientChannel", cause)
                    ctx.close()
                    peerChannel.close()
                  }
                })

                peerChannel.config().setAutoRead(true)
                clientChannel.config().setAutoRead(true)
              })
            }).isEmpty) {
              logger.warn(s"unknown localAddress ip type: ${localAddress.getAddress.getClass}")

              peerChannel.close()
              clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
            }
          })
        }
        case _ => clientChannel.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, Socks5AddressType.IPv4, "0.0.0.0", 0)).addListener((f: ChannelFuture) => f.channel().close())
      }
    } else ctx.close()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    state match {
      case 0 => handleState0(ctx, msg)
      case 1 => handleState1(ctx, msg)
      case 2 => handleState2(ctx, msg)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    logger.error("unexpected exception on clientChannel", cause)
    ctx.close()
  }
}
