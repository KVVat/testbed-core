package org.example.project.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server

object PrintServerMethods {
    @JvmStatic
    fun main(args: Array<String>) {
        Server::class.java.methods.forEach {
            println(it)
        }
    }
}
