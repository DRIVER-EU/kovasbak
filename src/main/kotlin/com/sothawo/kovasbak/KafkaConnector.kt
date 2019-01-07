package com.sothawo.kovasbak

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * @author P.J. Meisch (pj.meisch@sothawo.com)
 */

interface KafkaConnectorListener {
    fun chatMessage(topic: String, user: String, message: String)
}

@Component
class KafkaConnector {

    @Value("\${spring.kafka.template.default-topic:kovasbak-chat}")
    val defaultTopic: String = "kovasbak-chat"

    @Value("#{'\${chat.rooms:kovasbak-chat}'.split(',')}")
    val topics = listOf<String>()

    final val kafkaUsername: String?

    val listeners = mutableListOf<KafkaConnectorListener>()

    fun addListener(listener: KafkaConnectorListener) {
        listeners += listener
    }

    fun removeListener(listener: KafkaConnectorListener) {
        listeners -= listener
    }

    @Suppress("SpringKotlinAutowiring")
    @Autowired
    lateinit var kafka: KafkaTemplate<String, String>

    /*

     */
    constructor(@Value("\${spring.kafka.properties.ssl.keystore.location:#{null}}") tlsKeystoreLocation: String?, @Value("\${spring.kafka.properties.ssl.keystore.type:#{null}}") tlsKeystoreType: String?, @Value("\${spring.kafka.properties.ssl.keystore.password:#{null}}") tlsKeystorePass: String?, @Value("\${spring.kafka.properties.ssl.key.password:#{null}}") tlsKeyPass: String?) {
        kafkaUsername = if (tlsKeystoreLocation == null) {
            null
        } else {
            /*
Set the username to the subject name (CN) from SSL certificate if using one (e.g. for authentication)
*/
            val ks = KeyStore.getInstance(tlsKeystoreType)
            val tlsKeystoreIs = FileInputStream(File(tlsKeystoreLocation))
            tlsKeystoreIs.use {
                ks.load(it, tlsKeystorePass?.toCharArray())
            }

            getCertSubjectCN(ks, tlsKeyPass)
        }
    }


    fun send(topic: String, user: String, message: String) {
        log.info("$user sending message \"$message\" on topic '$topic'")
        kafka.send(topic, user, message)
    }


    /*
    See Spring Kafka's EnableKafkaIntegrationTests class for examples of spring property injection
     */
    @KafkaListener(topics = arrayOf("#{'\${chat.rooms:kovasbak-chat}'.split(',')}"), group = "\$spring.kafka.consumer.group-id:")
    fun receive(consumerRecord: ConsumerRecord<String?, String?>) {
        val key: String = consumerRecord.key() ?: "???"
        val value: String = consumerRecord.value() ?: "???"
        val topic: String = consumerRecord.topic() ?: "???"
        log.info("Received kafka record with key '$key' and value '$value' on topic '$topic'")
        listeners.forEach { listener -> listener.chatMessage(topic, key, value) }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(KafkaConnector::class.java)

        private val CERT_SUBJECT_DN_REGEX = "^(|.+,)CN=([^,]+).*".toRegex()

        fun getCertSubjectCN(loadedKs: KeyStore, keyPass: String?): String {
            val ksEntryAliases = loadedKs.aliases()
            var privKeyEntry: KeyStore.PrivateKeyEntry? = null
            var privKeyAlias: String? = null
            while (ksEntryAliases.hasMoreElements()) {
                val ksEntryAlias = ksEntryAliases.nextElement()
                if (loadedKs.isKeyEntry(ksEntryAlias)) {
                    val keyEntry = loadedKs.getEntry(ksEntryAlias, KeyStore.PasswordProtection(keyPass?.toCharArray()))
                    if (keyEntry is KeyStore.PrivateKeyEntry) {
                        privKeyEntry = keyEntry
                        privKeyAlias = ksEntryAlias
                    }
                }
            }

            if (privKeyEntry == null) {
                throw IllegalArgumentException("No PrivateKeyEntry in the keystore")
            }

            val cert = privKeyEntry.certificate as? X509Certificate
                    ?: throw IllegalArgumentException("Keystore's PrivateKeyEntry '$privKeyAlias' has a certificate but not X.509")
            val dnMatchResult = CERT_SUBJECT_DN_REGEX.matchEntire(cert.subjectX500Principal.name)
                    ?: throw IllegalArgumentException("Invalid subject DN of certificate in keystore's PrivateKeyEntry '$privKeyAlias': '${cert.subjectX500Principal.name}'; expected pattern: $CERT_SUBJECT_DN_REGEX")
            return dnMatchResult.groupValues[2]
        }
    }

}

/*
fun main(args: Array<String>) {
    val dn = "CN=Admin Tool,OU=Authz Service Dev Project,OU=WP923,O=DRIVER-PROJECT.EU"
    val dnMatchResult = KafkaConnector.CERT_SUBJECT_DN_REGEX.matchEntire(dn)
    val groups = dnMatchResult?.groupValues
    print(groups?.get(2))
}*/
