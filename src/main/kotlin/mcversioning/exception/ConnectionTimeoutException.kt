package mcversioning.exception

import mcversioning.util.PathUtils

class ConnectionTimeoutException(url: String, more: String)
    : BaseException("连接超时(${PathUtils.getFileNamePart(url)}): $url ($more)")