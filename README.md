# MythicItemFixer

MythicMobs 4.12 / Minecraft 1.15.2 時代のMMアイテムを、Paper 1.21.11 + MythicMobs 5 でもMMアイテムとして
扱えるようにするプラグインです(1.21.11 専用)。

MythicMobs 5 はアイテムの `mythicmobs:type`(PDC)でMMアイテムを見分けます。このプラグインは次の2つの方法で
`mythicmobs:type` を付けます。アイテムは作り直さず、名前・Lore・エンチャント・耐久値は変更しません。

## 1. 旧タグが残っているアイテム

1.15.2 のアイテムにあった `MYTHIC_TYPE` は、1.21.11 への移行後も `minecraft:custom_data` に残っています。
これを見つけたら同じIDを `mythicmobs:type` として付けます(Prevent* フラグも引き継ぎます)。

- プレイヤーのインベントリ・エンダーチェスト・カーソル(参加時と定期走査)
- 開いたインベントリ、拾った・捨てたアイテム
- 読み込まれたチャンクのチェストなど、ドロップ・額縁・Mobの装備(エンティティはチャンクより遅れて読み込まれるので、その時点で処理)
- シュルカーボックスとバンドルの中身
- StorageBox(`xyz.acrylicstyle.storageBox`)の箱の中身。箱はインベントリの外にアイテムを持つため、
  プレイヤーのインベントリ走査・`/mmidfix scan`・`/mmidfix worldscan`で箱を見つけたときに中身へ同じ処理を行い、
  StorageBoxのAPIで箱を書き戻します
- オフラインプレイヤーの `world/playerdata/*.dat`(起動時に自動。変更前のファイルは `.mmidcompat.bak` に一度だけ保存。処理中は新規ログインを待たせます)
- `/mmidfix worldscan` で全リージョンのチャンクを順に読み込んで処理(`full-world-chunks-per-tick`、既定1)

## 2. 旧タグも失われたアイテム(インベントリ修復)

元の1.15.2 `MythicMobs/Items` の **中身** を `plugins/MythicItemFixer/legacy-mythic-items/` に置きます
(SIS用の `plugins/SearchableInfiniteShop/legacy-mythic-items/` があればそこも参照します)。
旧定義と見比べて一致するIDが1つだけのとき、そのIDを付けます。

- 比べるもの:材質・表示名・Lore・ヘッドのスキンURL(色コードの違いは無視)
- 材質は現在のMythicMobsがそのIDで生成するアイテムから取ります。`Id: 397` + `Data: 3` のような数値ID や、1.15 から名前が変わった材質でも照合できます。現在のMMに無いIDは対象外です。
- 見た目が同じ別物を除外するため、定義が決めている次の特徴と食い違うアイテムには付けません。
  - CustomModelData が旧定義・現定義のどちらの Model とも違う
  - 定義が `Unbreakable: true` なのに壊れるアイテム
  - 定義の `Color` と革防具の色が違う
- CustomModelData とヘッドのスキンが欠けていれば補います。
- 属性(AttributeModifiers)は、現在のMM定義にある属性のうちアイテムに1つも無いものを補います。既に付いている属性はそのままです。MMID付与済みのアイテムも対象です。
- Lore の文字は変更しません。同じMMIDでもアイテムごとにLoreが違う(Loreで性能を持たせている)ものがあるためです。アイテムを丸ごと作り直すこともしません。装飾だけの違いは 3. で揃えます。
- 表示名は、名前が無い・文字は同じで色や装飾だけ崩れている・1.15.2の定義の名前のまま(定義側で改名された)のいずれかなら、現在のMM定義の名前に直します。金床などで別の名前に変えたものはそのままです。

プレイヤー参加時・ワールド移動時・チェストなどを閉じた直後に、そのプレイヤーのインベントリとエンダーチェストを自動修復します(`repair-inventory-on-join` / `repair-inventory-on-world-change` / `repair-inventory-on-close`)。
インベントリ内のStorageBoxは、箱の中身にも同じ修復を行います。
旧定義との照合はチェスト、ワールド、他のプレイヤーには作用しません(チェストから取り出したアイテムは、閉じた時点で修復されます)。

## 3. `/mm i get` のアイテムと重なるようにする

1.15.2 から移行したアイテムは、中身は同じでも書き方が今のMMの生成物と違うため、スタックできないことがあります。
MMIDが分かったアイテム(`mythicmobs:type` 付き、または 2. で特定できたもの)は、次の部分を今のMMの生成物に揃えます。

- 名前:名前が無い・文字が同じ・1.15.2の定義の名前のまま、のどれか。金床で付けた名前はそのまま残します
- Lore:全行の文字が今の定義か1.15.2の定義と同じ。Lv1/Lv2や個別ステータスなど文字が違うLoreはそのまま残し、そのアイテムは整形自体を行いません
- custom_data:PDC、MythicMobs 4 の名残(`MYTHIC_*`・`Prevent*`)、1.15.2の古いタグ(`HideFlags`など)、今のMMのアイテムにもあるキーしか無い。他プラグインのPDCは引き継ぎます
- ヘッドのスキン(`profile`):テクスチャが同じでUUIDだけ違う
- スタックできるアイテム(最大スタック数2以上)のみ:定義だけが決める部分(表示設定 `tooltip_display`、Model、属性、耐久無限、エンチャントの光など)
- 生成するたびに内容が変わる(ランダム要素のある)定義は対象外

エンチャント・耐久値・金床の使用歴・装飾など、プレイヤーが個別に変えられる部分はアイテム自身のままです。
揃えても重ならないアイテムは `/mmidfix check` に「重なりません」と理由付きで出ます。手に持って `/mmidfix hand` で、今の定義と違う部分を値付きで確認できます。

インベントリ修復(2.)に加え、1. の走査(チェスト、開いたインベントリ、ドロップなど)でも `mythicmobs:type` 付きのアイテムに行います。
MMの定義を変えたら `/mmidfix reload` してください(生成物をキャッシュしています)。

## 除外設定

`config.yml` の `excluded-mmids` に書いたMMIDのアイテムは、1(旧タグからのID付与。オンライン・チャンク・オフラインすべて)も2(ID・Model・スキン・属性・名前の修復)も3(スタック用の整形)も行いません。大文字小文字は区別せず、末尾が `*` なら前方一致です。

```yaml
excluded-mmids:
  - SomeItem   # 完全一致
  - Event_*    # Event_ で始まるMMIDすべて
```

また、今のMMの定義がただのバニラアイテム(MM自身の `mythicmobs:*` タグ以外に何も付かない)のMMIDも、自動で除外扱いになります(`exclude-vanilla-definitions`、既定 `true`)。
1.15.2で普通のアイテムだったものにMMIDを付けて、バニラのアイテムと重ならなくしてしまうのを防ぐためです。
さらに、そのMMIDが付いていてもMMのタグ以外はバニラと同じアイテムは、タグを外して普通のバニラアイテムに戻します(自動修復・走査・`/mmidfix`・Storage Boxの中身・`repairItem` API)。
SearchableInfiniteShop の取引も同じ規則で、該当するMMIDの取引はバニラアイテムとして扱います(次回の保存でファイルにも反映)。件数は `/mmidfix status` に出ます。

変更後は `/mmidfix reload` で反映されます。

## コマンド

`/mmidfix`・`inventory`・`check`・`hand`・`help` は権限 `mythicitemfixer.use`(既定で全員)、それ以外は `mythicitemfixer.admin`(既定OP)が必要です。

| コマンド | 権限 | 内容 |
|:--|:--|:--|
| `/mmidfix` | use | 自分のインベントリとエンダーチェストを修復(シュルカー・バンドル・Storage Boxの中身も) |
| `/mmidfix inventory [preview]` | use | `/mmidfix` と同じ(`preview` 付きは `/mmidfix check` と同じ) |
| `/mmidfix check` | use | 何が修復されるか・重ならないアイテムとその理由を表示(変更しない) |
| `/mmidfix hand` | use | 手持ちのアイテムを修復し、`/mm i get` と違う部分を値付きで表示 |
| `/mmidfix status` | admin | 処理件数と旧定義の読込状況 |
| `/mmidfix reload` | admin | 設定と旧定義を読み直す(MMのアイテムを変更・リロードした後に) |
| `/mmidfix scan` | admin | オンラインプレイヤーと読込済みチャンクを走査 |
| `/mmidfix offline` | admin | オフラインプレイヤーファイルを再走査 |
| `/mmidfix worldscan` | admin | 全チャンクを順に読み込んで走査 |

## 他プラグインから使うAPI

`MythicItemFixer` の次の public メソッドを、リフレクションなどで呼び出せます(AziSync と StorageBox が使っています)。

| メソッド | スレッド | 内容 |
|:--|:--|:--|
| `preserveMythicId(ItemStack)` | メイン | 1つのアイテムに旧タグからIDを付与 |
| `preserveMythicIds(ItemStack[])` | メイン | 配列内の全アイテムに同上 |
| `isMythicIdExcluded(String)` | 任意 | `excluded-mmids` に含まれるか |
| `repairPlayerInventory(Player)` | メイン | 参加時と同じ自動修復(旧タグ変換+旧定義との照合)を今すぐ実行 |
| `resolveMythicId(ItemStack)` | メイン | アイテムを変更せずにMMIDを調べる(mythicmobs:type → 旧タグ → 旧定義との照合)。StorageBoxが使用 |
| `repairItem(ItemStack)` | メイン | 1つのアイテムに `/mmidfix` と同じ修復を行ったコピーを返す(変更が無ければnull)。StorageBoxが中身に使用 |
| `currentMythicItem(String)` | メイン | 今のMythicMobsが生成するそのIDのアイテム(1個) |

他プラグインの独自データベースや独自ファイル内のアイテムは、そのプラグインが上のAPIを呼ばない限り対象外です。
旧タグも、名前・Loreなどの手掛かりも失われたアイテムから元のIDを復元することはできません。
実行前にプレイヤーデータとワールドをバックアップしてください。
