package com.notificationmaster.data.filter

enum class Op {
    EQ, NEQ, LT, LTE, GT, GTE, LIKE, IN, IS_NULL, IS_NOT_NULL;

    companion object {
        fun fromName(name: String): Op = values().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown Op: $name")
    }
}
