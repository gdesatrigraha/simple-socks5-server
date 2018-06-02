package me.dijedodol.socks5.server.simple

import java.net.{HttpURLConnection, InetSocketAddress, Proxy, URL}

import com.typesafe.scalalogging.LazyLogging
import me.dijedodol.socks5.server.simple.config.{AuthConfig, Socks5Config}
import org.scalatest.FlatSpec

class Socks5ServerSpec extends FlatSpec with LazyLogging {

  it should "get the same response when connecting through the proxy server" in {
    val socks5Config = Socks5Config(
      bindAddress = InetSocketAddress.createUnresolved("0.0.0.0", 1080),
      authConfig = AuthConfig(username = None, password = None))

    val socks5Server = new Socks5Server(socks5Config)

    val targetUrl = new URL("http://ifconfig.me")
    val proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", 1080))

    try {
      socks5Server.start()

      val directHttpURLConnection = prepareHttpUrlConnection(targetUrl.openConnection.asInstanceOf[HttpURLConnection])
      val directHttpResponse = getResponseBodyAsString(directHttpURLConnection)
      logger.info(s"directHttpResponse: ${directHttpResponse}")

      val proxiedHttpURLConnection = prepareHttpUrlConnection(targetUrl.openConnection(proxy).asInstanceOf[HttpURLConnection])
      val proxiedHttpResponse = getResponseBodyAsString(proxiedHttpURLConnection)
      logger.info(s"proxiedHttpResponse: ${proxiedHttpResponse}")

      assert(directHttpResponse.equals(proxiedHttpResponse))
    } finally {
      socks5Server.stop()
    }
  }

  private def prepareHttpUrlConnection(httpURLConnection: HttpURLConnection): HttpURLConnection = {
    httpURLConnection.setRequestMethod("GET")
    httpURLConnection.setDoInput(true)
    httpURLConnection.setDoOutput(false)
    httpURLConnection.setRequestProperty("Host", "ifconfig.me")
    httpURLConnection.setRequestProperty("Accept", "text/plain")
    httpURLConnection.setRequestProperty("User-Agent", "curl/7.60.0")
    httpURLConnection
  }

  private def getResponseBodyAsString(connection: HttpURLConnection): String = {
    val readBuffer = new Array[Byte](256)
    val sb = new StringBuilder

    val src = connection.getInputStream
    val srcContentLength = connection.getContentLength

    var totalBytesRead = 0
    while (totalBytesRead < srcContentLength || srcContentLength == -1) {
      val bytesRead = src.read(readBuffer, 0, readBuffer.length)
      if (bytesRead == -1) {
        return sb.toString
      } else {
        totalBytesRead += bytesRead
        sb.append(new String(readBuffer, 0, bytesRead))
      }
    }

    src.close()
    sb.toString()
  }
}
