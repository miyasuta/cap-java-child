# 手順書：親pomによる中央ビルド構成（CAP Java × GitHub Packages × SAP CI/CD）

親pom が依存ライブラリの版を一元管理し、複数の子（CAP Java アプリ）が `<parent>` 座標参照だけでその版を継承する構成の構築手順。
**親pom の版pinを書き換えて固定版でリリースするだけで、各子は Renovate の自動 PR で `<parent>` 版を更新し新しい依存を取り込む**ことを目的とする。

> 設計の背景・判断は [PLAN-emoji-plugin-sample.md](../plan/PLAN-emoji-plugin-sample.md) / [PLAN-promotion-model.md](../plan/PLAN-promotion-model.md) を参照。
> 親→子の版上げ自動展開（Renovate＋PR検証＋auto-merge）の構築手順は [GUIDE-promotion-model.md](GUIDE-promotion-model.md)。
> （旧）SNAPSHOT 自動方式・CI/CD API トリガの検討は [PLAN-cicd-trigger-children.md](../plan/PLAN-cicd-trigger-children.md) / [PLAN-api-trigger.md](../plan/PLAN-api-trigger.md)。

## 全体構成

```
GitHub repos                         GitHub Packages (Maven Registry)
├── cap-java-emoji-plugin     ──┐    com.example.cap.plugins:emoji-plugin:1.1.0 / 1.2.0  (固定版)
├── cap-java-parent-pom-sample ─┤    com.example.central:central-parent:2.0.0 / 2.1.0    (固定版)
└── cap-java-child (consumer)  ──┘
        │                                   ▲
        │ <parent> 座標参照(固定版)          │ mvn -N deploy
        │ emoji-plugin 版指定なし(親から継承) │
        ▼                                   │
   SAP CI/CD 子ジョブ (CF, mta) ── settings.xml + PAT で上記を解決 → ビルド & デプロイ

  親の版上げ → Renovate が子の <parent> 版を PR で更新 → PR検証(test)緑で auto-merge → main push で CI/CD
```

### 版設計（重要）

| 対象 | 版方式 | 役割 |
|---|---|---|
| **emoji-plugin** | **固定版**（`1.1.0` → `1.2.0`、不変） | 依存ライブラリ本体。版を上げる＝親pinを書き換える離散イベント |
| **親pom の emoji-plugin pin** | 固定版を指す | これを書き換えて親版を上げる＝「親リリース」 |
| **親pom 自身**（子が `<parent>` 参照） | **固定版**（`2.0.0` → `2.1.0`） | 版上げが git 変更＝再現可能。子は **Renovate が `<parent>` 版を PR で更新** |

> emoji-plugin を固定版にすることで版上げが必然的に親pom更新（pin書き換え）になる。**親pom も固定版にする**ことで、版上げが各子の git 変更（`<parent>` 版の更新）として残り、PROD に入っている版の特定・再現や段階昇格が可能になる。子の追従は `mvn -U` ではなく Renovate が `<parent>` 版を PR で更新する（[GUIDE-promotion-model.md](GUIDE-promotion-model.md)）。
>
> 設計判断（固定版 vs SNAPSHOT の比較）は [PLAN-promotion-model.md](../plan/PLAN-promotion-model.md) §3 を参照。旧 SNAPSHOT 自動方式（親=SNAPSHOT、子=`-U`追従）は機構実証用に [PLAN-cicd-trigger-children.md](../plan/PLAN-cicd-trigger-children.md) に残る。

---

## ステップ1：emoji-plugin を GitHub Packages に publish

### 1-1. `pom.xml` に `<distributionManagement>` を追加

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <name>GitHub miyasuta Packages</name>
    <url>https://maven.pkg.github.com/miyasuta/cap-java-emoji-plugin</url>
  </repository>
  <snapshotRepository>
    <id>github</id>
    <name>GitHub miyasuta Packages</name>
    <url>https://maven.pkg.github.com/miyasuta/cap-java-emoji-plugin</url>
  </snapshotRepository>
</distributionManagement>
```

- owner 名は**小文字必須**（`miyasuta`）。`<id>github</id>` は次の settings.xml の `<server>` と一致させる。
- 固定版運用：`pom.xml` の `<version>` を `1.0.0`（📚）/ `1.1.0`（🚀）/ `1.2.0`（🎉）と上げていく。GitHub Packages は固定版の上書き不可（不変）なので、版差は別版 publish で表す。

### 1-2. `~/.m2/settings.xml` を作成（認証情報）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                              https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>github</id>
      <username>miyasuta</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

- PAT はベタ書きせず `${env.GITHUB_TOKEN}` で環境変数参照。

### 1-3. PAT (classic) を作成し環境変数に設定

- GitHub → Settings → Developer settings → Personal access tokens **(classic)**
- スコープ：**`write:packages`**（`read:packages` を含む）
- ⚠️ GitHub Packages は **classic PAT のみ**対応（fine-grained 不可）

```bash
export GITHUB_TOKEN=ghp_あなたのPAT
```

### 1-4. publish

```bash
cd emoji-plugin
mvn clean deploy
```

GitHub repo → Packages に `emoji-plugin 1.0.0` が出れば成功。

---

## ステップ2：親pom を作成・publish

### 2-1. 親pom を作成（`packaging: pom`）

要点（全体は [parent-pom/pom.xml](../../parent-pom/pom.xml) 参照）：

```xml
<groupId>com.example.central</groupId>
<artifactId>central-parent</artifactId>
<version>2.1.0</version>            <!-- 親も固定版（版上げ=親リリース） -->
<packaging>pom</packaging>

<properties>
  <!-- 中央で一元管理。子はこれを継承し各自で版を指定しない -->
  <jdk.version>21</jdk.version>
  <cds.services.version>4.8.0</cds.services.version>      <!-- ※子(cds init)の生成版に合わせる -->
  <spring.boot.version>3.5.11</spring.boot.version>
  <emoji-plugin.version>1.2.0</emoji-plugin.version>      <!-- ← これを書き換えて親版を上げる=親リリース -->
</properties>

<dependencyManagement>
  <dependencies>
    <!-- cds-services-bom / spring-boot-dependencies を import -->
    <!-- emoji-plugin の版を固定 pin -->
    <dependency>
      <groupId>com.example.cap.plugins</groupId>
      <artifactId>emoji-plugin</artifactId>
      <version>${emoji-plugin.version}</version>
    </dependency>
  </dependencies>
</dependencyManagement>

<build>
  <pluginManagement>
    <!-- compiler 3.15.0 / cds-maven-plugin / spring-boot / surefire 3.5.5 / flatten 1.7.3 / enforcer 3.6.2 -->
  </pluginManagement>
</build>

<!-- 親pom自身の publish 先 -->
<distributionManagement>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/miyasuta/cap-java-parent-pom-sample</url>
  </repository>
  <snapshotRepository>
    <id>github</id>
    <url>https://maven.pkg.github.com/miyasuta/cap-java-parent-pom-sample</url>
  </snapshotRepository>
</distributionManagement>
```

> ⚠️ 版整合：`cds.services.version` / `spring.boot.version` は **子を `cds init --java` で生成したときの版に合わせる**（本検証では 4.8.0 / 3.5.11）。pluginManagement にも子が使う plugin（compiler/cds/spring-boot/surefire/flatten/enforcer）の版を揃えておくと、子は plugin 版も持たずに済む。

### 2-2. `~/.m2/settings.xml` に「取得元」を追加

親pom や emoji-plugin を**取得**するためのリポジトリ定義。`miyasuta/*` ワイルドカードで配下の全パッケージを1リポジトリで解決する。

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

> **なぜ pom の `<repositories>` ではダメか**：子は「親pom自身」を読む前に取得先を知る必要がある（ブートストラップ問題）。pom 内の `<repositories>` は親を読めた後にしか効かないため、親の取得元は **settings.xml 側**に置く。

### 2-3. publish ＋ ソースを push

```bash
cd parent-pom
mvn -N deploy                    # central-parent:2.1.0 を publish（固定版）
git commit -am "..." && git push # 親repoのソース更新
```

> ⚠️ emoji pin と**親自身の `<version>` の両方**を上げること。親版を上げ忘れると同一版の再 publish になり GitHub Packages が **409 Conflict** を返す。

---

## ステップ3：子プロジェクトを作成（親参照・版継承）

`cds init --java` で生成した標準 CAP Java アプリを、中央 parent 参照に変換する。

### 3-1. emoji-plugin を依存に追加（版指定なし）

`srv/pom.xml`：
```xml
<dependency>
  <groupId>com.example.cap.plugins</groupId>
  <artifactId>emoji-plugin</artifactId>
  <!-- 版は親の dependencyManagement から継承するので指定しない -->
</dependency>
```
取得元は明示不要（`~/.m2/settings.xml` の `miyasuta/*` で解決）。`@emoji` 付きフィールドを持つ CDS モデルを用意する。

### 3-2. ルート pom に親参照を追加

```xml
<parent>
  <groupId>com.example.central</groupId>
  <artifactId>central-parent</artifactId>
  <version>2.1.0</version>   <!-- 固定版。以後は Renovate が PR でこの版を上げる -->
  <relativePath/>            <!-- 空＝local .m2/リモートから座標解決 -->
</parent>
```

### 3-3. ルート pom から「親に継承される分」を削除

- **版プロパティ**：`jdk.version` / `cds.services.version` / `spring.boot.version`（親から継承）
- **`<dependencyManagement>`**（cds-services-bom / spring-boot-dependencies の import。親から継承）
- （任意）build 内の各 plugin の `<version>`（親 pluginManagement から継承）

> ⚠️ **`requireMavenVersion` の中の `<version>3.6.3</version>` は消さない**。これは plugin 版ではなく **enforcer ルールの引数**（「Maven 3.6.3 以上を要求」）。見分け方：`<plugin>` 直下の `<version>` ＝plugin版（消して継承可）／`<rules>` 内の `<version>` ＝ルール引数（消すと壊れる）。

### 3-4. ローカル実行で確認

```bash
mvn spring-boot:run        # 通常起動（~/.m2/settings.xml を使用）
mvn -U clean spring-boot:run  # 固定版を初回解決し直したいとき（親版を上げた直後など）
```
- `clean`：target を削除しフレッシュビルド（CDS生成物も再生成）
- `-U`：固定版でも初回はリモートを見にいかせる（キャッシュ未取得の親版を取得）
- OData の `@emoji` フィールドに 🎉（emoji-plugin 1.2.0）が付けば成功

> 固定版では「親更新を子に取り込む」のは Renovate が `<parent>` 版を上げる PR で行う（`-U` 追従ではない）。ローカル実行は現在 pin している版の確認用。

---

## ステップ4：CI/CD 設定（子プロジェクト）

クリーンな CI/CD 環境はキャッシュも `~/.m2` も空なので、親pom/emoji-plugin を GitHub Packages から取得する設定が必須。

### 4-1. 子リポジトリ直下に `settings.xml` をコミット

ローカルと同じ内容＋**Central を先頭**に置いて無駄な検索を削減（多数派の cds/spring は Central で解決＝github を叩かない）。

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>miyasuta</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>github</id>
      <repositories>
        <!-- ① Central を先に -->
        <repository>
          <id>central</id>
          <url>https://repo.maven.apache.org/maven2</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
        <!-- ② github は miyasuta製(central-parent / emoji-plugin)用 -->
        <repository>
          <id>github</id>
          <url>https://maven.pkg.github.com/miyasuta/*</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>github</activeProfile></activeProfiles>
</settings>
```

### 4-2. `mta.yaml` のビルドコマンドで settings.xml を参照

```yaml
build-parameters:
  builder: custom
  commands:
    # -s ../settings.xml: ワークスペース直下の settings.xml を使い、GitHub Packages から
    # 親pom(central-parent) と emoji-plugin を解決する。
    - mvn clean package -DskipTests=true --batch-mode -U -s ../settings.xml
  build-result: target/*-exec.jar
```

> ⚠️ **`~/.m2` へのコピーは効かない**：Additional Commands（`runFirst`）は mtaBuild とは**別コンテナ（node toolbox）**で動くため、そこで `~/.m2/settings.xml` に置いても build コンテナには届かない。コンテナ間で渡るのは**ワークスペースだけ**なので、**コミット済み settings.xml を `-s` で mvn に直接渡す**のが正解。`mbt` は custom builder を**モジュールディレクトリ（srv）**で実行するので相対パスは `../settings.xml`。

### 4-3. PAT を CI/CD 資格情報として登録（read 専用）

- `read:packages` のみの **classic PAT** を作成。
- SAP CI/CD で **「Secret Text」タイプ**の資格情報として登録（例：`github-readpackage`）。

### 4-4. Build ステージに Additional Credentials を登録

`.sap_cid/config.yaml`（CF Environment 3.0）：
```yaml
stages:
  build:
    buildTool: "mta"
    buildDescriptor: "mta.yaml"
    buildToolVersion: "MBTJ21N24"
    _additional:
      credentialVariables:
        - name: "GITHUB_TOKEN"            # 環境変数名（settings.xml の ${env.GITHUB_TOKEN} と一致）
          valueSource: "github-readpackage"  # 4-3 で作った資格情報名
```

これで子の CI/CD ジョブが GitHub Packages から親pom・emoji-plugin を解決し、ビルド＆デプロイできる。

---

## ステップ5：親→子の自動展開（Renovate）

親pom を固定版で publish すると、各子の `<parent>` 版を **Renovate** が GitHub Packages と突き合わせて検知し、**更新 PR を自動作成**する。PR で自動テスト（`test`）が緑なら **auto-merge**、main への push で CI/CD が起動する。

→ 構築手順（Renovate 設定・PR 検証ゲート・auto-merge）は [GUIDE-promotion-model.md](GUIDE-promotion-model.md) を参照。
> （旧方式）SNAPSHOT＋`-U` 追従や CI/CD API による独自トリガの検討は [PLAN-api-trigger.md](../plan/PLAN-api-trigger.md)。固定版＋Renovate ではこれらの独自トリガは**不要**。

---

## カスケードの実行（版を上げて子に反映）

```
① emoji-plugin の版を上げて publish    : ハンドラ変更 → pom version 1.1.0→1.2.0 → mvn -N deploy
② 親pin と親版を上げて publish          : emoji-plugin.version 1.1.0→1.2.0, 親 2.0.0→2.1.0 → mvn -N deploy
③ Renovate が子の <parent> を PR で更新 : 2.0.0→2.1.0 PR → test 緑で auto-merge → main push
   → 子の <parent> 版が更新され OData が 🚀→🎉
```

> ③は `-U` 再ビルドではなく Renovate の PR。契約が変わってテストが赤になればマージはブロックされる（安全網）。詳細は [GUIDE-promotion-model.md](GUIDE-promotion-model.md)。

---

## 補足：ハマりどころ早見表

| 症状 | 原因 | 対処 |
|---|---|---|
| `Non-resolvable parent POM ... central-parent ... absent` | settings.xml が mvn に渡っていない | mta.yaml に `-s ../settings.xml`（`~/.m2` コピーは別コンテナで無効） |
| `${env.GITHUB_TOKEN}` が空 | 資格情報が Basic Auth（`*_password` に割れる） | **Secret Text** タイプで登録 |
| github が全アーティファクトで叩かれ遅い | repo が groupId で絞れず全座標を問い合わせ | settings.xml で **central を先頭**に |
| enforcer が壊れる | `requireMavenVersion` の version を誤削除 | ルール引数なので残す |
| 親更新が子に反映されない | 固定版なので子の `<parent>` 版が上がるまで反映されない | Renovate の更新 PR をマージ（[GUIDE-promotion-model.md](GUIDE-promotion-model.md)） |
| ローカルだけ通り CI/CD で落ちる | ローカルは `~/.m2`＋キャッシュで隠れる | クリーン解決（キャッシュ削除→`-U`）で事前検証 |

### settings.xml は二系統（既定）

| ビルド方法 | 使われる settings.xml |
|---|---|
| 直接 `mvn`（spring-boot:run / install） | `~/.m2/settings.xml` |
| `mbt build` / CI/CD | プロジェクトルートの `settings.xml`（`-s ../settings.xml`） |

→ 片方だけ変えると食い違うので、変更時は**両方そろえる**。

#### `-s` で1本に統一する選択肢

`-s` はグローバルオプションなので **直接 `mvn` にも付けられる**（`spring-boot:run` 含む）。プロジェクトの settings.xml は自己完結（servers＋repositories）なので、これだけで解決でき、`~/.m2/settings.xml` 不要にもできる。

```bash
mvn -s settings.xml spring-boot:run     # プロジェクトルートから（ゴールの前後どちらでも可）
```

- `-s` は user settings（`~/.m2`）を**置換**する（マージではない）。中身が同じなら実害なし。
- パスは **CWD 相対**：ルート実行なら `-s settings.xml`、`srv` から（mbt）は `-s ../settings.xml`。
- 毎回打つのが面倒なら **`.mvn/maven.config`** に `--settings=settings.xml` を書けば自動適用（`-s` 不要に）。
  - ⚠️ ただし mbt は mta.yaml で `-s ../settings.xml` を明示するため、maven.config の `--settings=settings.xml`（`srv` から見ると存在しないパス）と**二重指定・パス食い違いの恐れ**。maven.config を使うなら**ローカル専用**と割り切るのが無難。

| やり方 | メリット | デメリット |
|---|---|---|
| 二系統（直接mvnは無印） | ローカルで何も付けず楽 | settings.xml が2ファイル（要同期） |
| 直接mvnも `-s settings.xml` | settings.xml 1本に統一できる | 毎回 `-s`／CWD相対パスに注意 |
| `.mvn/maven.config` | `-s` 自動適用 | mbt の `-s ../settings.xml` と干渉注意 |
