package com.velocity.app.domain

/**
 * Pure KMP parser, serializer, and self-hosted relay snippet generator for `velocity://` URIs.
 *
 * Canonical format:
 * `velocity://<pskHex>@<host>:<port>?name=<urlEncodedName>&region=<region>&mtu=<mtu>`
 */
object ServerUriCodec {

    private const val SCHEME = "velocity://"
    private const val DEFAULT_PORT = 51820
    private const val DEFAULT_MTU = 1380

    fun decode(rawUri: String): Result<ServerProfile> = parse(rawUri)

    fun encode(profile: ServerProfile): String = serialize(profile)

    fun parse(rawUri: String): Result<ServerProfile> = runCatching {
        val trimmed = rawUri.trim()
        require(trimmed.startsWith(SCHEME, ignoreCase = true)) {
            "URI must start with velocity://"
        }

        val withoutScheme = trimmed.substring(SCHEME.length)
        val queryIdx = withoutScheme.indexOf('?')
        val authorityPart = if (queryIdx >= 0) withoutScheme.substring(0, queryIdx) else withoutScheme
        val queryPart = if (queryIdx >= 0) withoutScheme.substring(queryIdx + 1) else ""

        val atIdx = authorityPart.indexOf('@')
        require(atIdx > 0 && atIdx < authorityPart.lastIndex) {
            "URI must include pre-shared key and host (velocity://<psk>@<host>:<port>)"
        }

        val psk = authorityPart.substring(0, atIdx).trim()
        require(isValidPsk(psk)) {
            "Pre-shared key must be at least 16 hex/alphanumeric characters"
        }

        val hostPort = authorityPart.substring(atIdx + 1).trim()
        val (host, port) = parseHostPort(hostPort)

        val params = parseQueryParams(queryPart)
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: host
        val region = params["region"]?.takeIf { it.isNotBlank() } ?: "Self-Hosted"
        val mtu = params["mtu"]?.toIntOrNull()?.coerceIn(1200, 1500) ?: DEFAULT_MTU
        val id = params["id"]?.takeIf { it.isNotBlank() }
            ?: "srv-${sanitizeId(host)}-$port"

        ServerProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            pskHex = psk.lowercase(),
            region = region,
            mtu = mtu,
            isSelfHosted = true,
        )
    }

    fun serialize(profile: ServerProfile): String {
        val hostFormatted = if (profile.host.contains(':') && !profile.host.startsWith("[")) {
            "[${profile.host}]"
        } else {
            profile.host
        }
        val query = buildString {
            append("name=").append(percentEncode(profile.name))
            append("&region=").append(percentEncode(profile.region))
            append("&mtu=").append(profile.mtu)
        }
        return "$SCHEME${profile.pskHex}@$hostFormatted:${profile.port}?$query"
    }

    fun oracleCloudSnippet(profile: ServerProfile): String = """
        # Run on Oracle Cloud Always Free Ubuntu ARM64 (VM.Standard.A1.Flex)
        sudo PSK=${profile.pskHex} \
          BIND_PORT=${profile.port} \
          MONTHLY_LIMIT_GB=10240 \
          ENFORCE_QUOTA=true \
          bash server/oci/install-oci.sh
    """.trimIndent()

    fun dockerSnippet(profile: ServerProfile): String = """
        docker run -d --name velocity-relay --restart unless-stopped \
          --cap-add=NET_ADMIN --device=/dev/net/tun \
          -p ${profile.port}:${profile.port}/udp \
          -p 8080:8080/tcp \
          -e PSK=${profile.pskHex} \
          -e BIND=0.0.0.0:${profile.port} \
          -e MTU=${profile.mtu} \
          -e MONTHLY_LIMIT_GB=10240 \
          ghcr.io/manassoni-2009/velocity-relay:latest
    """.trimIndent()

    fun linuxCliSnippet(profile: ServerProfile): String = """
        sudo sysctl -w net.ipv4.ip_forward=1
        sudo ./velocity-relay \
          --listen 0.0.0.0:${profile.port} \
          --psk ${profile.pskHex} \
          --tun-name vel0 \
          --tun-cidr 10.66.0.1/24 \
          --mtu ${profile.mtu} \
          --monthly-limit-gb 10240 \
          --enforce-quota \
          --usage-file /var/lib/velocity-relay/usage.json
    """.trimIndent()

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            require(closeBracket > 1) { "Invalid IPv6 host syntax" }
            val host = hostPort.substring(1, closeBracket)
            val rest = hostPort.substring(closeBracket + 1)
            val port = if (rest.startsWith(":")) {
                parsePort(rest.substring(1))
            } else {
                require(rest.isEmpty()) { "Unexpected trailing characters after IPv6 host" }
                DEFAULT_PORT
            }
            return host to port
        }

        val colonIdx = hostPort.lastIndexOf(':')
        return if (colonIdx > 0) {
            val host = hostPort.substring(0, colonIdx).trim()
            val port = parsePort(hostPort.substring(colonIdx + 1).trim())
            require(host.isNotEmpty()) { "Host cannot be empty" }
            host to port
        } else {
            require(hostPort.isNotEmpty()) { "Host cannot be empty" }
            hostPort to DEFAULT_PORT
        }
    }

    private fun parsePort(raw: String): Int {
        val port = raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid port: $raw")
        require(port in 1..65535) { "Port out of range (1..65535): $port" }
        return port
    }

    private fun isValidPsk(psk: String): Boolean {
        if (psk.length < 16) return false
        return psk.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-' || it == '_' }
    }

    private fun sanitizeId(host: String): String =
        host.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) {
                result[percentDecode(pair)] = ""
            } else {
                val key = percentDecode(pair.substring(0, eqIdx))
                val value = percentDecode(pair.substring(eqIdx + 1))
                result[key] = value
            }
        }
        return result
    }

    internal fun percentEncode(input: String): String {
        val bytes = input.encodeToByteArray()
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code ||
                c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            ) {
                out.append(c.toChar())
            } else if (c == ' '.code) {
                out.append("%20")
            } else {
                out.append('%')
                out.append(HEX_DIGITS[c ushr 4])
                out.append(HEX_DIGITS[c and 0x0F])
            }
        }
        return out.toString()
    }

    internal fun percentDecode(input: String): String {
        val bytes = ArrayList<Byte>(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                ch == '%' && i + 2 < input.length -> {
                    val hi = hexDigit(input[i + 1])
                    val lo = hexDigit(input[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                    } else {
                        bytes.add(ch.code.toByte())
                        i++
                    }
                }
                else -> {
                    val encoded = ch.toString().encodeToByteArray()
                    for (b in encoded) bytes.add(b)
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
