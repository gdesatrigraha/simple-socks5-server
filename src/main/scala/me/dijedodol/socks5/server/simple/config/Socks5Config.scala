package me.dijedodol.socks5.server.simple.config

import java.net.InetSocketAddress

case class Socks5Config(val bindAddress: InetSocketAddress, val authConfig: AuthConfig)
