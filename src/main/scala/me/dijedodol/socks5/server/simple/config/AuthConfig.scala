package me.dijedodol.socks5.server.simple.config

case class AuthConfig(val username: Option[String], val password: Option[String]) {

  def isAuthenticationRequired(): Boolean = username.isDefined || password.isDefined

  def isAuthenticationMatch(username: Option[String], password: Option[String]): Boolean = {
    this.username == username && this.password == password
  }
}
