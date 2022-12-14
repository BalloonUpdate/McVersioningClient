package mcversioning.exception

import mcversioning.util.PathUtils

class ConnectionRejectedException(url: String, more: String)
    : BaseException("连接被拒绝(${PathUtils.getFileNamePart(url)}): $url ($more)")