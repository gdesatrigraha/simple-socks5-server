package me.dijedodol.socks5.server.simple

import java.net.InetSocketAddress

import com.typesafe.scalalogging.LazyLogging
import me.dijedodol.socks5.server.simple.config.{AuthConfig, Socks5Config}
import org.apache.commons.cli.{DefaultParser, Options}

object App extends scala.App with LazyLogging {

  val argsOptions = new Options().addOption("b", true, "local network interface ip address & port (ip:port) to which the server will listen for incoming connection requests (default 0.0.0.0:1080)")
    .addOption("u", true, "authentication username (no auth if username & password are omitted)")
    .addOption("p", true, "authentication password (no auth if username & password are omitted)")

  val argsParser = new DefaultParser

  val cmdLine = argsParser.parse(argsOptions, args)

  val bindAddress = if (cmdLine.hasOption("b")) {
    val b = cmdLine.getOptionValue("b").split(":")
    InetSocketAddress.createUnresolved(b(0), b(1).toInt)
  } else Option(System.getenv("SSS_BIND")).map(f => {
    val b = f.split(":")
    InetSocketAddress.createUnresolved(b(0), b(1).toInt)
  }).getOrElse(InetSocketAddress.createUnresolved("0.0.0.0", 1080))

  val username = if (cmdLine.hasOption("u")) Some(cmdLine.getOptionValue("u")) else Option(System.getenv("SSS_USERNAME"))
  val password = if (cmdLine.hasOption("p")) Some(cmdLine.getOptionValue("p")) else Option(System.getenv("SSS_PASSWORD"))

  val socks5Config = Socks5Config(bindAddress = bindAddress, authConfig = AuthConfig(username = username, password = password))
  val socks5Server = new Socks5Server(socks5Config)

  try {
    socks5Server.start().closeFuture().sync()
  } catch {
    case f: Throwable => {
      logger.error("unexpected exception", f)
    }
  } finally {
    socks5Server.stop()
  }
}
