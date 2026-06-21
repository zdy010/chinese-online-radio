package com.radio.chinese.service

/**
 * 123云盘内置配置
 * 授权码用于解锁访问，WebDAV凭证用于连接网盘
 */
object YunPanConfig {
    /** 授权码（用户必须输入正确后才能使用） */
    const val AUTH_CODE = "xxxx"

    /** WebDAV 服务器地址 */
    const val WEBDAV_SERVER_URL = "xxx/webdav"

    /** WebDAV 用户名 */
    const val WEBDAV_USERNAME = "xxx"

    /** WebDAV 密码 */
    const val WEBDAV_PASSWORD = "xxx"
}
