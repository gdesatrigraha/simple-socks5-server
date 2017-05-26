FROM openjdk:8-alpine

ADD build/distributions/*.tar /app/
RUN ln -s /app/* /app/simple-socks5-server

CMD /app/simple-socks5-server/bin/simple-socks5-server
