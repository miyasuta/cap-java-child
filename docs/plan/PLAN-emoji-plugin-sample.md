# 検証用サンプル：emoji-plugin（依存バージョン変更を「動作」で確認する）

> このファイルは [PLAN-cicd-trigger-children.md](PLAN-cicd-trigger-children.md)（中央ビルド設計の概要）から分離した、**プラグイン側（依存アーティファクト）の詳細**です。
> 親pom更新→子ジョブ一括再ビルドの仕組みを、**実行中アプリの OData レスポンスで目視確認**するための題材を扱います。

## 1. 題材：CAP Java プラグイン `emoji-plugin`

`@emoji` アノテーション付きフィールドに絵文字を付与するプラグイン。**版ごとに絵文字を変える**ことで、依存バージョンの違いを実行中アプリの OData レスポンスで一目で確認できる。

- GroupId `com.example.cap.plugins` / ArtifactId `emoji-plugin`
- 公式: [Building Plugins | capire](https://cap.cloud.sap/docs/java/building-plugins)

### なぜこの題材が適切か

- プラグインは **Maven の JAR アーティファクト**そのものなので、`<dependency>` として普通に取り込める。
- 挙動が **実行中アプリの OData レスポンスに直接現れる**（例: `title` → `title 📚`）。
- 版で絵文字を変える（**v1.0 → 📚 / v2.0 → 🚀**、または出力に版数を埋める `title 📚 (plugin v1.0)`）ことで、再ビルドで新依存が反映されたことを**ログやpom差分でなく動作で証明**できる。

### 重要な制約

1. **利用側（子）は CAP Java アプリである必要がある**。プラグインは CAP の ServiceLoader（`META-INF/services/com.sap.cds.services.runtime.CdsRuntimeConfiguration`）とイベントハンドラに依存するため、素のJavaプロジェクトでは何も起きない。→ 中央ビルド設計の子プロジェクトも CAP Java で用意する。
2. **観測には「実行＋ODataアクセス」が必要**。ビルド成功だけでは絵文字は見えない。デモには `mvn spring-boot:run` → GET（または統合テスト）の手順を含める。
3. **最小構成なら CDSモデル部分は不要**。版差を見せるだけなら、子エンティティの任意フィールドに `@emoji` を付けるだけでよく、プラグインのアスペクト/型と `cds-maven-plugin` の `resolve` ゴールは省略可。CDSモデル（アスペクト・型）まで使う場合のみ resolve ゴールが必要。

## 2. プラグインの調達：既存リポジトリを再利用（新規作成しない）

プラグインは既存リポジトリ **[miyasuta/cap-java-emoji-plugin](https://github.com/miyasuta/cap-java-emoji-plugin)** の `emoji-plugin/` サブフォルダを**そのまま再利用**する。**新規リポジトリは作らない**。

### 配置：作業フォルダ `java-central-build-sample` 内にクローンして同居

`java-central-build-sample` 自体は **git 管理しないただの作業フォルダ**なので、その中にクローンしても git の入れ子問題は起きない（クローンした `cap-java-emoji-plugin` は**独自の `.git` を保持**し、変更は本家リポジトリへ push する）。一箇所に全部まとまっていて作業しやすい。

```
java-central-build-sample/         ← ただの作業フォルダ（git管理しない）
├── docs/plan/
├── cap-java-emoji-plugin/         ← ここにクローン（独自 .git、GitHubへpush）
│   ├── emoji-plugin/              ← ★これを publish 元に（独立した Maven プロジェクト）
│   ├── consume-emoji-plugin/      ← 今回の検証では使わない（無視）
│   └── .gitignore
├── parent-pom/                    ← java-central-build-sample 側で手作成
└── consumer-*/                    ← java-central-build-sample 側で手作成
```

クローンコマンド:
```bash
cd ~/projects/java-central-build-sample
git clone https://github.com/miyasuta/cap-java-emoji-plugin
```

**新規リポジトリを作らない理由**：

- **ルートに集約pom（reactor pom）が無い** → `emoji-plugin/` は完全に独立したモジュール。`cd cap-java-emoji-plugin/emoji-plugin && mvn deploy` でプラグインだけを publish でき、同居する `consume-emoji-plugin/` は一切干渉しない。
- 今回の検証で子が参照するのは**「Maven 座標で publish 済みの JAR」**であって、リポジトリのフォルダ構成ではない。レジストリに上がっていれば子は座標で取り込める（＝物理配置は自由）。
- 同居する `consume-emoji-plugin/` は害のない distractor。中央ビルド検証の「子アプリ」は `java-central-build-sample` 側で別途 `consumer-*` として用意する。

## 3. publish 先：GitHub Packages（Maven レジストリ）

emoji-plugin が既に GitHub 上にあるため、**GitHub Packages の Maven レジストリ**を使う。**publish はローカルから `mvn deploy`**（CI/CD ジョブ化はしない。プラグイン publish はデモのトリガを作る前段にすぎないため）。

- **レジストリURL**: `https://maven.pkg.github.com/miyasuta/cap-java-emoji-plugin`
  - owner 名は**小文字必須**（`miyasuta` はOK）。
- **版方式**: emoji-plugin は**固定版**（`1.0.0` → `1.1.0`）で publish。GitHub Packages は固定版の上書き不可＝不変なので、版差は別版 publish で表現する（[4章](#4-版設計emoji-plugin固定版--親pomsnapshot)参照）。
  - 親pom（`central-parent`）側は SNAPSHOT なので、消費側は `settings.xml` で snapshots を有効化する必要がある。

### ⚠️ 認証が常時必須・匿名ダウンロード不可（今回唯一の実質的な注意点）

GitHub Packages は publish だけでなく**ダウンロード（依存解決）にも認証が必要**で、匿名アクセスは不可。

- **publish 側**: `write:packages` スコープの PAT（classic）を `~/.m2/settings.xml` に登録。
- **消費側（中央ビルドの子アプリ・SAP CI/CD の子ジョブ）**: `read:packages` スコープの PAT を `settings.xml` に注入しないと依存解決が失敗する。
  - → **SAP CI/CD では PAT を Credentials に登録し、子ジョブの Maven `settings.xml` に注入する手当てが必要**（中央ビルド設計側の実装項目）。

### 設定例

**publish 側 pom（`distributionManagement`）**
```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/miyasuta/cap-java-emoji-plugin</url>
  </repository>
  <snapshotRepository>
    <id>github</id>
    <url>https://maven.pkg.github.com/miyasuta/cap-java-emoji-plugin</url>
  </snapshotRepository>
</distributionManagement>
```

**`~/.m2/settings.xml`（publish/消費 共通の資格情報）**
```xml
<servers>
  <server>
    <id>github</id>
    <username>miyasuta</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
</servers>
```

**消費側の依存解決リポジトリ**（`~/.m2/settings.xml`、ワイルドカードで1本化）

子は「親pom自身」と「emoji-plugin」を取得するが、**親を読む前に取得先が必要**なため、pom の `<repositories>` ではブートストラップできない。`miyasuta/*` ワイルドカードを `settings.xml` の profile に置き、`id=github` の1リポジトリで両方解決する（id は `<server>` と一致）。

```xml
<profiles>
  <profile>
    <id>github</id>
    <repositories>
      <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/miyasuta/*</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
      </repository>
    </repositories>
  </profile>
</profiles>
<activeProfiles>
  <activeProfile>github</activeProfile>
</activeProfiles>
```

## 4. 版設計：emoji-plugin=固定版 / 親pom=SNAPSHOT

検証の趣旨は「**親pom更新→子を一括再ビルド**」。これを実イベントとして成立させるため、登場する3つの版を次のように役割分担する。

| # | 版 | 値 | 動かす意味 | 子pom編集 |
|---|---|---|---|---|
| A | **emoji-plugin 自身** | `1.0.0` → `1.1.0`（**固定・不変**） | 新しい絵文字の中身 | 不要 |
| B | **親pom が pin する emoji-plugin の版**（`<dependencyManagement>`） | A に追従して書き換え | **＝親pom更新トリガ** | 不要 |
| C | **親pom 自身の版**（子が `<parent>` 参照） | `central-parent:1.0.0-SNAPSHOT`（**SNAPSHOT・可変**） | 子が新しい親を `-U` で拾える | 不要 |

> **なぜ emoji-plugin を SNAPSHOT にしないか**: SNAPSHOT で中身だけ差し替えると、**親pom を一切触らずに**子が新絵文字を拾えてしまい、「親pom更新」イベントが発生しない＝検証の趣旨を実演できない。固定版にすることで「版を上げる＝親pom(B)を書き換えてpush」という離散的トリガが必然になる。
>
> **なぜ親pom(C)を SNAPSHOT にするか**: 子が `<parent>` を SNAPSHOT 参照していれば、親再publish後に**子は `mvn -U` の単純再ビルドのみ**で新しい親（＝新しい固定版プラグイン）を取り込める。子pom無変更を保てる。親まで固定版にすると子の `<parent><version>` を毎回書き換える必要が生じ、Dependabot/Renovate が要る世界になる。

### 中央ビルド設計へのマッピング

- **親pom**（ローカル `java-central-build-sample/parent-pom`, `central-parent:1.0.0-SNAPSHOT`）: `emoji-plugin` の版を `<dependencyManagement>` で **固定 pin**。GitHub repo `miyasuta/cap-java-parent-pom-sample`（Packages）へ publish。
- **子（CAP Javaアプリ、`consumer-*`）**: `<parent>` に `central-parent:1.0.0-SNAPSHOT` を**座標参照**（`<relativePath/>` 空）。`emoji-plugin` は版指定なしで `<dependency>`（親の dependencyManagement から固定版を継承）。`@emoji` フィールドを1つ持つ。

### デモ手順（本プランの主想定）

1. `emoji-plugin 1.0.0`（📚）を `mvn deploy`（固定版・不変）
2. 親pom が `emoji-plugin.version = 1.0.0` を pin、`central-parent:1.0.0-SNAPSHOT` を publish
3. 子を `mvn -U` ビルド＆実行 → OData レスポンスに 📚
4. ハンドラを 🚀 に変えた `emoji-plugin 1.1.0` を `mvn deploy`（**新しい固定版**。GitHub Packages は固定版上書き不可＝不変）
5. **親pom の `emoji-plugin.version` を `1.1.0` に書き換えて push ＝親pom更新トリガ** → 親(SNAPSHOT)を再publish
6. 中央ビルドスクリプト（[trigger-children.js](PLAN-cicd-trigger-children.md)）が子ジョブを `mvn -U` 再ビルド → 実行 → レスポンスが 🚀
   → **子pom無変更**で、親pom更新だけで動作が変わることを実証できる。

## 5. このプラグイン側の進捗・残タスク

- [x] `emoji-plugin/` の pom に GitHub Packages 向け `distributionManagement` を追加
- [x] `~/.m2/settings.xml` に `github` サーバ資格情報（PAT, classic / `write:packages`）を設定
- [x] emoji-plugin を**固定版 `1.0.0`（📚）**にして `mvn deploy`（GitHub Packages に publish 済み）
- [x] 中央 parent-pom（`central-parent:1.0.0-SNAPSHOT`）作成・`emoji-plugin 1.0.0` を pin・local install
- [x] `~/.m2/settings.xml` に `miyasuta/*` ワイルドカード取得元（profile, activeByDefault）を追加
- [x] **GitHub repo `miyasuta/cap-java-parent-pom-sample` を作成** → parent-pom を `mvn deploy`（B方式の親publish）＋ pom ソースを git push 済み
- [x] **Phase 1**: 子 `consumer-a` を `cds init --java` で生成、`consume-emoji-plugin` を参考に CDSモデル（`@emoji` 付き）＋emoji-plugin依存を追加 → `mvn spring-boot:run` → OData レスポンスに 📚 確認（`title: "Clean Architecture 📚"`）
- [x] **クリーン解決の検証**: ローカル `~/.m2` から emoji-plugin を削除 → `mvn -U` で settings.xml の `miyasuta/*`＋PAT 経由 GitHub Packages から download されることを `_remote.repositories` の `>github=` で確認（＝本番CI/CD子ジョブと同じ経路）
- [x] 版整合: 中央 parent を cds **4.8.0** / SB **3.5.11**（＋compiler 3.15.0 / surefire 3.5.5 / flatten 1.7.3）に更新・再 install（生成アプリの現行版に親を合わせた）
- [x] **Phase 2**: `consumer-a` を `central-parent` の `<parent>` 座標参照に変換（版properties/BOM/plugin版を削除し親継承、emoji-plugin の版指定も削除）→ `mvn -U` で 📚 維持を確認。子は版を一切持たず親が統治
- [x] `consumer-a` を独自 GitHub リポジトリ（`cap-java-child`）へ push（子＝別CI/CDジョブの単位）
- [x] **子の SAP CI/CD ジョブ（CF, mta）でビルド＆デプロイ成功**。クリーン環境での親pom/emoji-plugin 解決を以下で解決:
  - PAT 注入: Secret Text 資格情報 `github-readpackage` → `_additional.credentialVariables` で env `GITHUB_TOKEN`
  - settings.xml: 子リポジトリ直下にコミット。`runFirst` で `~/.m2` へコピーは**別コンテナのため無効**だった → **mta.yaml の custom builder mvn に `-s ../settings.xml`** を付与して解決
  - 効率化: settings.xml で `central` を先頭に置き、github は miyasuta製のみ叩く構成に
- [x] 親pom（`cap-java-parent-pom-sample`）を 4.8.0/3.5.11 で GitHub Packages へ **deploy** 済み（CI/CD クリーン環境から解決可能に）
- [x] 消費側（子CI/CDジョブ）への PAT 注入方法を確定（Secret Text 資格情報＋`_additional.credentialVariables`＋mta.yaml `-s ../settings.xml`）
- [x] **本丸（カスケード実証）成功** ✅: emoji-plugin `1.1.0`（🚀）publish → 親pin を `1.1.0` に更新・再 deploy → **子CI/CD を手動再ビルド（`-U`）→ OData の `title` が 🚀 に変化**。子pom・子設定は無変更で「親pom更新だけで子の動作が変わる」を実証
- [ ] **トリガ自動化はトライアル不可のため文書化のみ**（[PLAN-cicd-trigger-children.md](PLAN-cicd-trigger-children.md) 9章）。トライアルは CI/CD API プラン無し・ジョブ上限2個 → 子起動は手動。`trigger-children.js`（API版）は本番設計として記載のみ

## 参考

- [Building Plugins | capire](https://cap.cloud.sap/docs/java/building-plugins)
- [How to build reusable plugin components for CAP Java applications | SAP Community](https://community.sap.com/t5/technology-blog-posts-by-sap/how-to-build-reusable-plugin-components-for-cap-java-applications/ba-p/13552211)
- [Working with the Apache Maven registry | GitHub Docs](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
- 既存プラグインリポジトリ: [miyasuta/cap-java-emoji-plugin](https://github.com/miyasuta/cap-java-emoji-plugin)
