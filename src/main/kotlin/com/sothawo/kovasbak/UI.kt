package com.sothawo.kovasbak

import com.vaadin.annotations.PreserveOnRefresh
import com.vaadin.annotations.Push
import com.vaadin.event.ShortcutAction
import com.vaadin.server.Sizeable
import com.vaadin.server.VaadinRequest
import com.vaadin.shared.ui.ContentMode
import com.vaadin.spring.annotation.SpringUI
import com.vaadin.ui.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

/**
 * @author P.J. Meisch (pj.meisch@sothawo.com)
 */
@SpringUI
@PreserveOnRefresh
@Push
class ChatUI : UI(), KafkaConnectorListener {

    lateinit var user: String
    val chatDisplay = ChatDisplay()
    val userLabel = Label()
    val chatRooms = mutableMapOf<String, ChatRoom>()

    @Autowired
    lateinit var kafkaConnector: KafkaConnector

    override fun init(vaadinRequest: VaadinRequest?) {
        val chatRoomsWithOrWithoutDefault = kafkaConnector.topics.map { it to ChatRoom(it) }.toMap()
        val nullableDefaultChatroom = chatRoomsWithOrWithoutDefault[kafkaConnector.defaultTopic]
        val defaultChatRoom: ChatRoom
        if (nullableDefaultChatroom == null) {
            defaultChatRoom = ChatRoom(kafkaConnector.defaultTopic)
            chatRooms[kafkaConnector.defaultTopic] = defaultChatRoom
        } else {
            defaultChatRoom = nullableDefaultChatroom
        }

        chatRooms.putAll(chatRoomsWithOrWithoutDefault)

        val chatRoomsComboBox = ComboBox<ChatRoom>()
        /*
        isEmptySelectionAllowed not longer supported in Vaadin 10 (only 7 o 8): https://github.com/vaadin/vaadin-combo-box-flow/issues/124
         */
        chatRoomsComboBox.isEmptySelectionAllowed = false
        chatRoomsComboBox.isTextInputAllowed = false
        chatRoomsComboBox.caption = "Room: "
        chatRoomsComboBox.setItems(chatRooms.values)
        chatRoomsComboBox.setItemCaptionGenerator(ChatRoom::name)
        chatRoomsComboBox.setSelectedItem(defaultChatRoom)

        chatRoomsComboBox.addValueChangeListener { event ->
            if (!event.source.isEmpty) {
                val selectedChatroom = event.value
                chatDisplay.setRoom(selectedChatroom)
            }

        }

        chatDisplay.setRoom(defaultChatRoom)

        kafkaConnector.addListener(this)
        content = VerticalLayout().apply {
            setSizeFull()
            addComponents(chatRoomsComboBox, chatDisplay, createInputs())
            setExpandRatio(chatDisplay, 1F)
        }

        val kafkaUsername = kafkaConnector.kafkaUsername
        user =
                if (kafkaUsername.isNullOrEmpty()) {
                    askForUserName()
                } else {
                    log.info("Username set to Kafka client certificate's subject CN: $kafkaUsername")
                    kafkaUsername
                }

        userLabel.value = kafkaUsername
    }

    override fun detach() {
        kafkaConnector.removeListener(this)
        super.detach()
        log.info("session ended for user $user")
    }

    private fun createInputs(): Component {
        return HorizontalLayout().apply {
            setWidth(100F, Sizeable.Unit.PERCENTAGE)
            val messageField = TextField().apply { setWidth(100F, Sizeable.Unit.PERCENTAGE) }
            val button = Button("Send").apply {
                setClickShortcut(ShortcutAction.KeyCode.ENTER)
                addClickListener {
                    chatDisplay.chatRoom?.let { r ->
                        kafkaConnector.send(r.name, user, messageField.value)
                        messageField.apply { clear(); focus() }
                    }
                }
            }
            addComponents(userLabel, messageField, button)
            setComponentAlignment(userLabel, Alignment.MIDDLE_LEFT)
            setExpandRatio(messageField, 1F)
        }
    }

    private fun askForUserName(): String {
        var inputUser: String? = null
        addWindow(Window("your user:").apply {
            isModal = true
            isClosable = false
            isResizable = false
            content = VerticalLayout().apply {
                val nameField = TextField().apply { focus() }
                addComponent(nameField)
                addComponent(Button("OK").apply {
                    setClickShortcut(ShortcutAction.KeyCode.ENTER)
                    addClickListener {
                        inputUser = nameField.value
                        if (!inputUser.isNullOrEmpty()) {
                            close()
                            log.info("user entered: $inputUser")
                        }
                    }
                })
            }
            center()
        })

        if (inputUser == null) {
            throw RuntimeException("User entered blank or no username")
        }

        return inputUser!!
    }

    override fun chatMessage(topic: String, user: String, message: String) {
        access {
            val chatRoom = chatRooms[topic]
            chatRoom?.let {
                it.addMessage(user, message)
                if (chatRoom.name == chatDisplay.chatRoom?.name) {
                    chatDisplay.syncText()
                }
            }
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(ChatUI::class.java)
    }
}

class ChatDisplay : Panel() {
    private val textLabel: Label
    var chatRoom: ChatRoom? = null

    init {
        setSizeFull()
        textLabel = Label().apply { contentMode = ContentMode.HTML }
        content = VerticalLayout().apply { addComponent(textLabel) }
    }

    fun setRoom(room: ChatRoom) {
        chatRoom = room
        caption = room.name
        syncText()
    }

    fun syncText() {
        textLabel.value = chatRoom?.text
        scrollTop = Int.MAX_VALUE
    }
}

class ChatRoom(roomName: String) {
    val name: String = roomName
    var text: String = ""

    fun addMessage(user: String, message: String) {
        text = when {
            text.isEmpty() -> "<em>$user:</em> $message"
            else -> "$text<br/><em>$user:</em> $message"
        }
    }
}

