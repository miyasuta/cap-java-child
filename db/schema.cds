namespace my.book;
using { com.example.cap.plugins.emoji as emoji } from 'com.example.cap.plugins/emoji-plugin';

entity Books: emoji.emoji {  // ← プラグイン由来のアスペクトを継承
  key ID    : UUID;
  @emoji                 // ← 絵文字付与の対象フィールド
  title     : String(200);
  author    : String(200);
  emoji     : emoji.EmojiString;  // ← プラグイン由来の型を使用
}
