import 'dart:async';
import 'dart:typed_data';

import 'package:assets_audio_player/assets_audio_player.dart';
import 'package:bluebubbles/blocs/message_bloc.dart';
import 'package:bluebubbles/helpers/utils.dart';
import 'package:bluebubbles/layouts/conversation_view/messages_view.dart';
import 'package:bluebubbles/managers/attachment_info_bloc.dart';
import 'package:bluebubbles/repository/models/attachment.dart';
import 'package:bluebubbles/repository/models/chat.dart';
import 'package:bluebubbles/repository/models/message.dart';
import 'package:flutter/material.dart';
import 'package:metadata_fetch/metadata_fetch.dart';
import 'package:video_player/video_player.dart';

/// Holds cached metadata for the currently opened chat
///
/// This allows us to get around passing data through the trees and we can just store it here
class CurrentChat {
  StreamController _stream = StreamController.broadcast();

  Stream get stream => _stream.stream;

  StreamController<Map<String, List<Attachment>>> _attachmentStream =
      StreamController.broadcast();

  Stream get attachmentStream => _attachmentStream.stream;

  Chat chat;

  Map<String, Uint8List> imageData = {};
  Map<String, Metadata> urlPreviews = {};
  Map<String, VideoPlayerController> currentPlayingVideo = {};
  Map<String, AssetsAudioPlayer> currentPlayingAudio = {};
  List<VideoPlayerController> videoControllersToDispose = [];
  List<AssetsAudioPlayer> audioControllersToDispose = [];
  List<Attachment> chatAttachments = [];
  List<Message> sentMessages = [];
  OverlayEntry entry;

  Map<String, List<Attachment>> messageAttachments = {};

  CurrentChat(this.chat);

  factory CurrentChat.getCurrentChat(Chat chat) {
    CurrentChat currentChat = AttachmentInfoBloc().getCurrentChat(chat.guid);
    if (currentChat == null) {
      currentChat = CurrentChat(chat);
      AttachmentInfoBloc().addCurrentChat(currentChat);
    }

    return currentChat;
  }

  /// Initialize all the values for the currently open chat
  /// @param [chat] the chat object you are initializing for
  void init() {
    dispose();

    imageData = {};
    currentPlayingVideo = {};
    currentPlayingAudio = {};
    urlPreviews = {};
    videoControllersToDispose = [];
    audioControllersToDispose = [];
    chatAttachments = [];
    sentMessages = [];
    entry = null;
  }

  static CurrentChat of(BuildContext context) {
    assert(context != null);
    return context.findAncestorStateOfType<MessagesViewState>()?.currentChat ??
        null;
  }

  /// Fetch and store all of the attachments for a [message]
  /// @param [message] the message you want to fetch for
  List<Attachment> getAttachmentsForMessage(Message message) {
    // If we have already disposed, do nothing
    if (chat == null) return [];
    if (!messageAttachments.containsKey(message.guid)) {
      preloadMessageAttachments(specificMessages: [message]).then(
        (value) => _attachmentStream.sink.add(
          {message.guid: messageAttachments[message.guid]},
        ),
      );
      return [];
    }
    return messageAttachments[message.guid];
  }

  List<Attachment> updateExistingAttachments(MessageBlocEvent event) {
    String oldGuid = event.oldGuid;
    if (!messageAttachments.containsKey(oldGuid)) return [];
    Message message = event.message;

    messageAttachments.remove(oldGuid);
    messageAttachments[message.guid] = message.attachments;
    if (message.attachments.isEmpty) return [];

    String newAttachmentGuid = message.attachments.first.guid;
    if (imageData.containsKey(oldGuid)) {
      Uint8List data = imageData.remove(oldGuid);
      imageData[newAttachmentGuid] = data;
    } else if (currentPlayingVideo.containsKey(oldGuid)) {
      VideoPlayerController data = currentPlayingVideo.remove(oldGuid);
      currentPlayingVideo[newAttachmentGuid] = data;
    } else if (currentPlayingAudio.containsKey(oldGuid)) {
      AssetsAudioPlayer data = currentPlayingAudio.remove(oldGuid);
      currentPlayingAudio[newAttachmentGuid] = data;
    } else if (urlPreviews.containsKey(oldGuid)) {
      Metadata data = urlPreviews.remove(oldGuid);
      urlPreviews[newAttachmentGuid] = data;
    }
    return message.attachments;
  }

  Uint8List getImageData(Attachment attachment) {
    if (!imageData.containsKey(attachment.guid)) return null;
    return imageData[attachment.guid];
  }

  void saveImageData(Uint8List data, Attachment attachment) {
    imageData[attachment.guid] = data;
  }

  void clearImageData(Attachment attachment) {}

  Future<void> preloadMessageAttachments(
      {List<Message> specificMessages}) async {
    assert(chat != null);
    List<Message> messages = specificMessages != null
        ? specificMessages
        : await Chat.getMessages(chat, limit: 25);
    for (Message message in messages) {
      if (message.hasAttachments) {
        List<Attachment> attachments = await message.fetchAttachments();
        messageAttachments[message.guid] = attachments;
      }
    }
  }

  /// Retreive all of the attachments associated with a chat
  Future<void> updateChatAttachments() async {
    chatAttachments = await Chat.getAttachments(chat);
  }

  void changeCurrentPlayingVideo(Map<String, VideoPlayerController> video) {
    if (!isNullOrEmpty(currentPlayingVideo)) {
      currentPlayingVideo.values.forEach((element) {
        videoControllersToDispose.add(element);
        element = null;
      });
    }
    currentPlayingVideo = video;
    _stream.sink.add(null);
  }

  void changeCurrentPlayingAudio(Map<String, AssetsAudioPlayer> audio) {
    if (!isNullOrEmpty(currentPlayingAudio)) {
      currentPlayingAudio.values.forEach((element) {
        audioControllersToDispose.add(element);
        element = null;
      });
    }
    currentPlayingAudio = audio;
    _stream.sink.add(null);
  }

  /// Dispose all of the controllers and whatnot
  void dispose() {
    if (!isNullOrEmpty(currentPlayingVideo)) {
      currentPlayingVideo.values.forEach((element) {
        element.dispose();
      });
    }

    if (!isNullOrEmpty(currentPlayingAudio)) {
      currentPlayingAudio.values.forEach((element) {
        element.dispose();
      });
    }

    imageData = {};
    currentPlayingVideo = {};
    currentPlayingAudio = {};
    urlPreviews = {};
    videoControllersToDispose = [];
    audioControllersToDispose = [];
    chatAttachments = [];
    sentMessages = [];
    if (entry != null) entry.remove();
  }

  /// Dipose of the controllers which we no longer need
  void disposeControllers() {
    videoControllersToDispose.forEach((element) {
      element.dispose();
    });
    videoControllersToDispose = [];

    audioControllersToDispose.forEach((element) {
      element.dispose();
    });
    audioControllersToDispose = [];
  }
}
