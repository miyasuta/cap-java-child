# 親pom更新時に子ジョブを一括再ビルドする仕組み — 調査・設計プラン

> このファイルは設計メモ（成果物）です。別ディレクトリ／別リポジトリへ移動して使用する前提で記述しています。
> 対象は CAP/Fiori とは無関係の Java(Maven) プロジェクト群と、それを束ねる SAP CI/CD サービスです。

## 1. 背景・目的

- Java プロジェクトで、**親pom（GitHub上）が参照する依存のバージョンが更新**されたとき、それを参照する**複数の子（使用先）プロジェクトを再ビルド＆再デプロイ**したい。
- ビルド基盤は **SAP Continuous Integration and Delivery（CI/CD）サービス**を使用する（制約）。
- 子ジョブは **数十個**。**すべて同一チームが管理**。
- 版設計は **親pom=SNAPSHOT／ライブラリ本体=固定版**（後述。**親pom の依存pinを書き換えてpush** が「親pom更新トリガ」、子は親SNAPSHOTを `mvn -U` 再ビルドで拾うので「子pomを変更せず再ビルドのみ」で取り込める）。検証サンプルの具体は [PLAN-emoji-plugin-sample.md](PLAN-emoji-plugin-sample.md) の4章。

### Maven の前提知識（npm との違い）

- Maven には標準のロックファイルが無い。依存は**毎ビルド時に pom の記述から解決**され、再現性は「pomに書いた版」＋「リリース版は不変(immutable)」という規約で担保される。
- **固定版（例 `1.2.3`）**: 再ビルドだけでは更新されない。pom のバージョンを書き換えてコミットが必要。← **ライブラリ本体（emoji-plugin）はこちら**。版を上げる＝親pomの依存pinを書き換える離散イベント＝親pom更新トリガ。
- **SNAPSHOT（例 `1.2.3-SNAPSHOT`）**: 可変。SNAPSHOTを再publishし、子を `mvn -U`（update-snapshots）付きで再ビルドすれば、**子pomを変更せず新しい中身を取り込める**。
- 親子の参照: **子が親を SNAPSHOT 参照していれば、親再publish＋子 `-U` 再ビルドで反映される**。← **今回の設計（親pom=SNAPSHOT）はこれ**。親pomが固定版プラグインの新版をpinし直して再publishされると、子はpom無変更で新プラグインを取り込む。

## 2. アーキテクチャ判断

### 検討した2案

| | A. 中央オーケストレーション | B. イベント駆動 |
|---|---|---|
| 対象リストの所有 | 親／中央スクリプト | 各子が登録（opt-in、分散） |
| 親は子を知る必要 | 必要 | 不要 |
| 作るもの | スクリプト1本 | ブローカ＋登録基盤＋イベント転送 |
| 疎結合の価値 | 低 | 高（別チーム所有・頻繁な増減で効く） |

### 決定：**A（中央オーケストレーション）を採用**

理由:
- **同一チーム所有**のため、B の主目的（親が子を知らなくてよい／チーム独立 opt-in）の価値がほぼ無い。
- 対象リストは親リポジトリ内に明示管理する（**対象リスト方式**、後述）。同一チーム所有なので、リストの保守コストは許容範囲。
- B のディスパッチャ・サブスクリプション基盤は本ケースでは明確にオーバーエンジニアリング。

> 対象指定は当初「命名規約フィルタ（`GET /v2/jobs` を絞り込む）」も検討したが、**命名規約が統一されている保証がない**ため、**対象ジョブ名を明示列挙する方式（`JOB_NAMES`）を採用**する。

### 配置：**親pomリポジトリの CI/CD ジョブ内「追加ステップ（Node.js）」として実行**

- フロー: 開発者が親pomの依存を更新して push → 親ジョブが webhook で起動 → 親SNAPSHOTを build & **publish（`mvn deploy`）** → **追加ステップで子ジョブN個をAPI起動**。
- 外部インフラ（cron/別サービス）不要で「push→カスケード」が自動成立。凝集度・認証情報・ログが CI/CD 内に一元化される。
- 事前定義ジョブの追加ステップで Node.js スクリプトを呼べることは確認済み。

> 補足: B（イベント駆動）が将来必要になっても、A で実装するトークン取得〜トリガのロジックはそのままディスパッチャ内部で再利用でき、手戻りにならない。

## 3. SAP CI/CD サービス API まとめ

- **API有効化**: サービスの Settings で「API usage」を有効化。
- **認証情報**: API用サービスインスタンス＋サービスキーを作成 → `url`(APIベース)・`tokenUrl`・`clientid`・`clientsecret`。
- **認証方式**: OAuth2 Client Credentials。`POST {tokenUrl}`（Basic認証 `clientid:clientsecret`, `grant_type=client_credentials`）でトークン取得 → 以降 `Authorization: Bearer <token>`。
- **API仕様の確認**: サービスUIのURLのホスト名以降を `/v2` に置換すると、テナント固有の OpenAPI/スキーマが見られる。
- **サービスキーは無期限** → 定期ローテーション推奨。

### 使用するエンドポイント

| 用途 | メソッド/パス | 確認状況 |
|---|---|---|
| ジョブ一覧取得 | `GET {url}/v2/jobs` | ドキュメントで確認 |
| ビルド状態取得 | `GET {url}/v2/jobs/{jobName}/builds/{buildNumber}` | curl 実例で確認済み |
| **ビルド起動** | `POST {url}/v2/jobs/{jobName}/builds`（body: branch 等） | REST規約から確度高だが**実機の `/v2` OpenAPI でパス・ボディを最終確認**すること |

> ⚠️ **API バージョン注意**: v2 ジョブには移行アナウンス（〜2026/1/15 で従来稼働）があり、新CFパイプライン／新APIバージョンへ移行している可能性がある。実機テナントで現行バージョンを必ず確認する。

## 4. スクリプト設計（`trigger-children.js`）

### 言語選定：Node.js を採用

「Javaプロジェクトだから Java で書く」という直感はこのケースには当てはまらない。本スクリプトは **CI/CD 追加ステップで動くオーケストレーションのグルーコード**であり、アプリの成果物に同梱されず、アプリの型・ライブラリ・ビルドも共有しない。よって**プロジェクトの言語**ではなく、**タスクの性質と実行環境**で選ぶのが正しい。

タスクは「REST を OAuth で叩く I/O 主体＋JSON パース＋並列制御＋結果集計」。

| 言語 | 適合度 | コメント |
|---|---|---|
| **Node.js** | ◎ | `fetch` 標準(18+)、JSON ネイティブ、Promise で並列プール容易、**依存ゼロ単一ファイル**。用途に最も素直。前提は「CIイメージに Node がある」ことのみ（追加ステップで Node 実行可と確認済み）。 |
| bash + curl + jq | △ | HTTP は得意だが、並列制御・JSON 解析(jq 依存)・エラー処理が数十ジョブ規模で煩雑＆脆い。`jq` 不在リスクも。ロジックが極小なら可。 |
| Java | ✗(本用途) | `HttpClient` のボイラープレート＋JSON ライブラリ(Jackson/Gson)が必要。single-file 実行でもJSON処理が苦しい。手間最大・見返り最小。組織ポリシーで強制される場合のみ。 |

→ **Node.js（依存ゼロ単一ファイル）を採用**。

### 方針

**依存ゼロの Node 単一ファイル**。Node 18+ の標準 `fetch` を使用し `npm install` 不要 → 追加ステップで `node ci/trigger-children.js` のみで動作。

### 入力（すべて環境変数 / CI/CD Credentials 経由。ハードコード禁止）

- `CICD_API_URL` — APIベースURL（サービスキーの `url`）
- `CICD_TOKEN_URL` — OAuthトークンURL
- `CICD_CLIENT_ID` / `CICD_CLIENT_SECRET` — サービスキー資格情報
- `TARGET_BRANCH` — 子をビルドするブランチ
- `JOB_NAMES` — **対象ジョブ名の明示リスト（カンマ区切り）**。本方式の中核。
  - 親リポジトリ内の設定ファイル（例 `ci/children.json`）から読む形でも可。リストの所在を一箇所に集約し、追加・削除をレビュー可能にする。
  - **親ジョブ自身はリストに含めない**（これだけで無限ループは回避できる）。
- `CONCURRENCY` — 同時起動数（既定 5）
- `DRY_RUN` — 既定 false（true なら起動せず対象だけ出力）
- `FAIL_ON_ANY` — 既定 false（部分失敗を warn 扱いにしステップは緑のまま）

### 処理フロー

1. `POST {CICD_TOKEN_URL}`（Basic認証, `grant_type=client_credentials`）→ access_token
2. 対象決定: `JOB_NAMES`（または `ci/children.json`）から対象ジョブ名の配列を読み込む
   - 任意: `GET {CICD_API_URL}/v2/jobs` で実在チェックし、リストに存在しないジョブ名があれば warn（タイプミス検出）
3. 同時実行数 `CONCURRENCY` を上限に `POST {CICD_API_URL}/v2/jobs/{job}/builds`（body にブランチ等）
4. 起動成否を集計してサマリ出力。**fire-and-forget**（既定では完了待ちしない）
5. `FAIL_ON_ANY=true` のときのみ、1件でも失敗で非ゼロ終了

> 完了ゲート（全子が緑でないと親リリース不可）が必要になった場合は、後段に `GET .../builds/{n}` のポーリング版を追加（親ジョブのブロック時間は増える）。

## 5. 実装上の注意点（外すと事故る）

1. **ステージ順序**: 追加ステップは必ず親SNAPSHOTの **publish（`mvn deploy`）成功後**に実行。前だと子が旧アーティファクトを取り込む。事前定義パイプラインは差し込み位置が固定なことがあるため要確認。
2. **自己トリガ除外**: 対象リストに親ジョブ自身を含めない（無限ループ防止）。
3. **fire-and-forget 既定**: 数十の完了待ちは親を長時間ブロックしライフサイクルを密結合化するため避ける。
4. **並列度制御**: 数十同時投入はビルドエージェントを枯渇させるため `CONCURRENCY` で制限。
5. **資格情報**: clientid/secret は CI/CD の Credentials に登録し環境変数で参照。コードに直書きしない。
6. **トリガ失敗の扱い**: 既定では1件失敗でも親ビルドを赤にせず warn＋集計（`FAIL_ON_ANY` で切替）。
7. **SNAPSHOT更新**: 子ジョブのビルドが `mvn -U`（update-snapshots）相当で最新SNAPSHOTを取りに行く設定か確認。

## 6. 未確定・要確認事項（実機で確認）

- [ ] `POST /v2/jobs/{jobName}/builds` の正確なパスとリクエストボディ（実機 `/v2` OpenAPI）
- [ ] 現行の API バージョン（v2 か、新パイプライン／新版か）
- [ ] 事前定義パイプラインの追加ステップが **publish より後**に実行されるか
- [ ] 対象ジョブ名リストの確定（`JOB_NAMES` または `ci/children.json` に列挙する正確なジョブ名）
- [ ] 子ジョブが親SNAPSHOT（`central-parent:1.0.0-SNAPSHOT`）を `mvn -U` で取得する設定になっているか
- [x] 版設計を決定：**emoji-plugin=固定版／親pom=SNAPSHOT**（親pom更新がトリガ、子は単純 `-U` 再ビルド）。詳細は [PLAN-emoji-plugin-sample.md](PLAN-emoji-plugin-sample.md) 4章

## 7. 検証用サンプル（概要）

「依存のバージョンが変わったことが動作でわかる」サンプルとして、**CAP Java プラグイン `emoji-plugin`** を依存に採用する。版ごとに絵文字を変えることで、依存バージョンの違いを実行中アプリの OData レスポンスで一目で確認できる。

> 📄 **詳細は別ファイル**: [PLAN-emoji-plugin-sample.md](PLAN-emoji-plugin-sample.md)（プラグインの調達・publish・認証・デモ手順）

要点だけ：

- **プラグインは既存リポジトリ [miyasuta/cap-java-emoji-plugin](https://github.com/miyasuta/cap-java-emoji-plugin) の `emoji-plugin/` を再利用**（新規作成しない。reactor pom が無く独立 publish 可、同居の consumer は無視）。
- **publish 先は GitHub Packages**。emoji-plugin は `miyasuta/cap-java-emoji-plugin`、親pom（`central-parent`）は `miyasuta/cap-java-parent-pom-sample` の Packages へ **ローカル `mvn deploy`**。
- **版設計：emoji-plugin=固定版（1.0.0→1.1.0）／親pom=SNAPSHOT**。親pom の依存pin書き換え＆再publish が「親pom更新トリガ」、子は `mvn -U` の単純再ビルド・pom無変更で取り込む（詳細は別ファイル4章）。
- ⚠️ GitHub Packages は**ダウンロードも認証必須（匿名不可）** → **子アプリ・子CI/CDジョブにも `read:packages` PAT を `settings.xml` で注入**が必要（SAP CI/CD では Credentials 登録）。
- **中央ビルド設計側で作るもの**（`java-central-build-sample`）: 親pom（`central-parent`, `emoji-plugin` 版を `<dependencyManagement>` で固定 pin）＋ `consumer-*`（CAP Java 子アプリ、`@emoji` フィールドを持ち親pomを `<parent>` 座標継承）。

## 8. 次のステップ

1. 上記「6. 要確認事項」を実機で確認。
2. `trigger-children.js` 雛形を作成（依存ゼロ / 対象リスト方式（`JOB_NAMES` または `ci/children.json`）/ fire-and-forget 既定）。
3. `POST .../builds` のパス・ボディを実機 OpenAPI に合わせて確定。
4. 親ジョブの追加ステップ（publish後）に組み込み、`DRY_RUN=true` で対象抽出を検証 → 本実行。

## 9. 実機検証メモ（2026-06 時点・トライアル）

### トライアルの制約（重要）

- SAP CI/CD のトライアルで使えるプランは **「trial (Application)」のみ**。**API用サービスインスタンス／サービスキーを作るプランが無い** → **CI/CD API が使えない**（`POST /v2/jobs/.../builds` 不可）。
- **ジョブ上限2個・逐次実行**。親＋子1個でちょうど上限。「数十の子」はトライアルでは実演不可（設計としては documented）。

### 決定：トリガは手動／API設計は文書化のみ

- **トライアルでは子ジョブの再ビルドを手動で起動**してカスケード（親pin更新→子で 🚀）を実証する。
- **API ベースの親ジョブ自動トリガ（下記「本番設計」）は plan に記載するのみ**。有償プラン／API 有効化が可能な環境で実装する想定。

### 親ジョブの本番設計（API が使える環境向け）

```
親repo(cap-java-parent-pom-sample) に push（pin更新）
  → webhook で親ジョブ起動
  ① mvn deploy で親SNAPSHOTを GitHub Packages に publish（write:packages PAT＋distributionManagement）
  ② publish成功後、trigger-children.js が CI/CD API で子ジョブN個を起動
       - OAuth2 client credentials（サービスキー: url/tokenUrl/clientid/clientsecret）
       - POST {url}/v2/jobs/{jobName}/builds（要・実機 /v2 OpenAPI 確認）
       - JOB_NAMES 列挙・親自身は除外・fire-and-forget
```

未確定（実機で要確認）:
- ① `mvn deploy` を SAP CI/CD ジョブでどう実行するか（定義済みパイプラインはCFアプリデプロイ前提。maven artifact の publish は Additional Commands 等で要工夫。Additional Commands は node toolbox コンテナで動き maven 不在の可能性）。
- ② トリガ API の正確な path/body と現行APIバージョン。
- ③ publish の**後**に Node スクリプトを走らせる順序の担保（post hook）。

> API 不可環境での代替（未採用）: 親が各子repoに空コミットを push → webhook で子起動。子repoへの write PAT のみで API 不要。trigger-children のロジック（対象リスト・除外・fire-and-forget）はそのまま流用可。

### 子CI/CDジョブで判明した知見（クリーン環境での private 依存解決）

子ジョブ（CF, mta, トライアル）で親pom／emoji-plugin を GitHub Packages から解決するのに必要だった設定:

1. **PAT 注入**: Secret Text 資格情報を `_additional.credentialVariables` で env `GITHUB_TOKEN` に展開（Basic認証だと `*_user`/`*_password` の2変数に割れるので **Secret Text** が正解）。
2. **settings.xml を mvn に読ませる**: `runFirst`(Additional Commands) で `~/.m2` にコピーは **別コンテナのため無効**。**mta.yaml の custom builder の mvn に `-s ../settings.xml`** を付与（settings.xml は子repo直下にコミット、`${env.GITHUB_TOKEN}` 参照）。
3. **取得元の無駄削減**: settings.xml で `central` を先頭に置き、`miyasuta/*`(github) は親pom/emoji-plugin だけ叩く構成に。

## 参考

- SAP Help: Continuous Integration and Delivery (CI/CD)
  https://help.sap.com/docs/btp/sap-business-technology-platform/continuous-integration-and-delivery-ci-cd
- CAP Docs: Deploy using CI/CD Pipelines
  https://cap.cloud.sap/docs/guides/deploy/cicd
- The New Cloud Foundry Pipeline in SAP CI/CD（バージョン移行）
  https://community.sap.com/t5/technology-blog-posts-by-sap/the-new-cloud-foundry-pipeline-in-sap-continuous-integration-and-delivery/ba-p/14240330
- How to Configure CI/CD with SAP CI/CD Service
  https://blog.sap-press.com/how-to-configure-ci-cd-with-sap-continuous-integration-and-delivery-service

> プラグイン（emoji-plugin）関連の参考リンクは [PLAN-emoji-plugin-sample.md](PLAN-emoji-plugin-sample.md) に分離した。
