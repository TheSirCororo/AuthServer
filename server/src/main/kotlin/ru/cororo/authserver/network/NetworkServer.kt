package ru.cororo.authserver.network

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import ru.cororo.authserver.logger

object NetworkServer {
    lateinit var serverChannel: Channel

    fun bind(hostname: String, port: Int) {
        val bossGroup = NioEventLoopGroup()
        val childGroup = NioEventLoopGroup()
        try {
            val serverBootstrap = ServerBootstrap()
                .channel(NioServerSocketChannel::class.java)
                .group(bossGroup, childGroup)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(ServerChannelInitializer())

            serverChannel = serverBootstrap.bind(hostname, port).channel()
            logger.info("Server bound to $hostname:$port")
            serverChannel.closeFuture().syncUninterruptibly()
        } finally {
            bossGroup.shutdownGracefully()
            childGroup.shutdownGracefully()
        }
    }
}