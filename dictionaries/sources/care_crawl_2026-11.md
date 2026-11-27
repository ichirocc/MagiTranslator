# 介護・医療語彙の収集記録（2026-11-25）

辞書に介護・医療の語を加えるため、ニュースサイトで実際に使われている語と頻度を調べた記録です。
**記事本文はリポジトリに保存していません。** 本文は頻度を数えるためだけに一時的に使い、残しているのは URL と語の出現数だけです。
翻訳の対応（日本語⇄ネパール語）はニュースからは得られないため、人が決めています。自信のない語は `../review/care_candidates.tsv` に回しました。

## 対象サイトと判断

| サイト | 言語 | 使った？ | 理由 |
|---|---|---|---|
| Joint（www.joint-kaigo.com） | 日本語（介護専門） | 使った（40 記事、2026 年の記事一覧から） | robots.txt は管理画面以外を許可 |
| Onlinekhabar（www.onlinekhabar.com） | ネパール語 | 使った（47 記事、サイト内検索から） | robots.txt に制限なし |
| Setopati（www.setopati.com） | ネパール語 | 使った（8 記事、サイト内検索から） | robots.txt はログイン・管理画面以外を許可 |
| NHK（www3.nhk.or.jp） | 日本語 | **使わなかった** | robots.txt が AI 用のクローラー（ClaudeBot、anthropic-ai など）をすべて拒否 |
| Kantipur（ekantipur.com） | ネパール語 | **使わなかった** | robots.txt の「content signals」で AI 利用の可否が示されていないため、念のため除外 |

- 取得のしかた：2 秒以上の間隔を空けて 1 件ずつ取得。ログインが必要なページや管理画面には触れていない
- ネパール語の検索語：केयरगिभर、हेरचाह、जेष्ठ नागरिक、वृद्धाश्रम、नर्सिङ、जापान

## 日本語（Joint 40 記事）での出現数（抜粋）

制度・サービスの語が中心でした。現場の介助の語（入浴、排泄、移乗など）は、ニュースにはほぼ出てきません。

ケアマネ 208、訪問介護 131、通所介護 121、利用者 55、ケアマネジャー 47、介護保険 25、特養 22、老健 20、送迎 13、訪問看護 10、
在宅 8、家族 8、有料老人ホーム 7、ケアプラン 7、介護職 6、デイサービス 5、看取り 5、医師 5、認知症 4、介護福祉士 4、ヘルパー 4、
介護職員 3、グループホーム 3、入浴・排泄・移乗・車椅子・おむつ・服薬・血圧・夜勤・申し送り 0

## ネパール語（Onlinekhabar・Setopati 55 記事）での出現数（抜粋）

नर्स 137、नर्सिङ 44、मुटु 43、केयरगिभर 21、रगत 20、बिरामी 16、अस्पताल 13、वृद्ध 6、हृदयाघात 6、रक्तचाप 5、उपचार 5、मधुमेह 4、
स्वास्थ्यकर्मी 4、भर्ना 4、स्ट्रोक 4、औषधि 3、स्याहार 3、ज्येष्ठ 2、जेष्ठ 2、वृद्धाश्रम 2、कब्जियत 2、शौच 2、दिसा 2、हेरचाह 1、अल्जाइमर 1、
अपाङ्गता 1、बान्ता 1、क्यान्सर 1、पक्षाघात 0、डिमेन्सिया 0、स्मृतिभ्रंश 0、ह्वीलचियर 0

## 辞書への反映

- **介護職の呼び方**：ネパール語のニュースは借用語 केयरगिभर（21 回）を使っていた。そのため 介護士・介護職員 → केयरगिभर とした
- **脳卒中**：ニュースは स्ट्रोक（4 回）を使い、पक्षाघात（0 回）は使っていなかったので、स्ट्रोक を採用した
- **高齢者**：ज्येष्ठ／जेष्ठ नागरिक の両方の表記があったので、ネ→日の辞書に両方を入れた
- **ニュースに出てこない現場の語**（認知症の訳語、入浴介助、デイサービス、特定技能など）は、根拠がないので有効な辞書に入れず、`../review/care_candidates.tsv` でネパール語話者の確認待ちにした

## 取得した記事の URL

### Joint
- https://www.joint-kaigo.com/articles/50698/
- https://www.joint-kaigo.com/articles/50685/
- https://www.joint-kaigo.com/articles/50676/
- https://www.joint-kaigo.com/articles/50663/
- https://www.joint-kaigo.com/articles/50651/
- https://www.joint-kaigo.com/articles/50640/
- https://www.joint-kaigo.com/articles/50630/
- https://www.joint-kaigo.com/articles/50622/
- https://www.joint-kaigo.com/articles/50608/
- https://www.joint-kaigo.com/articles/50595/
- https://www.joint-kaigo.com/articles/50589/
- https://www.joint-kaigo.com/articles/50571/
- https://www.joint-kaigo.com/articles/50562/
- https://www.joint-kaigo.com/articles/50549/
- https://www.joint-kaigo.com/articles/50543/
- https://www.joint-kaigo.com/articles/50533/
- https://www.joint-kaigo.com/articles/50526/
- https://www.joint-kaigo.com/articles/50516/
- https://www.joint-kaigo.com/articles/50508/
- https://www.joint-kaigo.com/articles/50497/
- https://www.joint-kaigo.com/articles/50481/
- https://www.joint-kaigo.com/articles/50465/
- https://www.joint-kaigo.com/articles/50453/
- https://www.joint-kaigo.com/articles/50437/
- https://www.joint-kaigo.com/articles/50424/
- https://www.joint-kaigo.com/articles/50408/
- https://www.joint-kaigo.com/articles/50390/
- https://www.joint-kaigo.com/articles/50379/
- https://www.joint-kaigo.com/articles/50375/
- https://www.joint-kaigo.com/articles/50360/
- https://www.joint-kaigo.com/articles/50354/
- https://www.joint-kaigo.com/articles/50339/
- https://www.joint-kaigo.com/articles/50329/
- https://www.joint-kaigo.com/articles/50318/
- https://www.joint-kaigo.com/articles/50312/
- https://www.joint-kaigo.com/articles/50298/
- https://www.joint-kaigo.com/articles/50290/
- https://www.joint-kaigo.com/articles/50276/
- https://www.joint-kaigo.com/articles/50267/
- https://www.joint-kaigo.com/articles/50259/

### Onlinekhabar・Setopati
- https://www.onlinekhabar.com/2024/10/1550437/obesity-increases-during-the-festival-how-to-control-it
- https://www.onlinekhabar.com/2024/12/1582319/there-is-a-need-for-upheaval-of-leadership-in-the-nursing-field
- https://www.onlinekhabar.com/2024/12/1582322/partial-factionalism-has-increased-in-the-nursing-association-i-have-become-independent
- https://www.onlinekhabar.com/2025/01/1598369/mps-response-to-nursing-council-chairs-statement
- https://www.onlinekhabar.com/2025/04/1669954/heart-care-tips
- https://www.onlinekhabar.com/2025/05/1678669/does-a-breakdown-in-the-connection-between-the-stomach-and-the-brain-cause-depression
- https://www.onlinekhabar.com/2025/09/1758245/new-regulation-of-nursing-council-license-will-not-be-renewed-if-nurses-do-not-update-their-knowledge-and-skills
- https://www.onlinekhabar.com/2025/11/1798982/nursing-strike-agreement-to-add-members-of-professional-organizations-to-task-force-strike-postponed
- https://www.onlinekhabar.com/2025/12/1820277/applications-invited-for-miss-nepal-peace-nursing-beauty-pageant
- https://www.onlinekhabar.com/2025/12/1822398/misleading-information-about-caregivers-in-israel-department-requests-to-be-aware
- https://www.onlinekhabar.com/2026/01/1844941/rana-appointed-as-the-chairperson-of-the-nursing-council-who-will-be-in-other-positions
- https://www.onlinekhabar.com/2026/05/1940430/applications-for-caregivers-open-in-israel-2300-caregivers-sought
- https://www.onlinekhabar.com/2026/06/1949271/to-apply-for-a-caregiver-the-passport-must-be-valid-for-at-least-one-year
- https://www.onlinekhabar.com/2026/06/1950944/israel-caregiver-exam-postponed-due-to-poor-management-17000-candidates-in-confusion
- https://www.onlinekhabar.com/2026/06/1951050/the-postponed-english-language-test-for-caregivers-will-be-held-tomorrow
- https://www.onlinekhabar.com/2026/06/1958154/caregiver-in-israel-applicant-submits-police-report-later
- https://www.onlinekhabar.com/2026/06/1960284/15000-caregivers-took-the-english-language-test-1200-passed
- https://www.onlinekhabar.com/2026/06/1963755/department-investigates-caregiver-certificates-action-will-be-taken-if-found-fake
- https://www.onlinekhabar.com/2026/07/1971624/the-new-recruitment-process-will-only-begin-once-the-passed-caregivers-are-sent-to-israel
- https://www.onlinekhabar.com/2026/07/1973150/preparations-to-quickly-finalize-protocol-for-sending-home-based-caregivers-to-israel
- https://www.onlinekhabar.com/2026/07/1985792/nurse-jyoti-ranabhat-begins-satyagraha-at-the-nursing-council-premises
- https://www.onlinekhabar.com/2026/08/1989640/rosie-the-first-nurse-to-hold-a-phd-in-nursing-becomes-the-member-secretary-of-the-grants-commission
- https://www.onlinekhabar.com/2026/10/2032530/8-members-appointed-to-nursing-and-pharmacy-council-who-are-they
- https://www.onlinekhabar.com/2026/10/2040215/hokkaido-opens-in-sauraha-serving-japanese-cuisine
- https://www.onlinekhabar.com/2026/10/2040297/false-alert-of-9-9-magnitude-earthquake-in-japan-withdrawn-within-eight-minutes
- https://www.onlinekhabar.com/2026/10/2046272/decision-on-proposed-ambassador-to-japan-depends-on-foreign-ministrys-opinion
- https://www.onlinekhabar.com/2026/10/2047007/moving-your-legs-is-also-a-workout-how-to-stay-active-throughout-the-day-without-taking-time-off
- https://www.onlinekhabar.com/2026/10/2047264/hearing-committee-meeting-called-to-decide-on-proposed-japanese-ambassador
- https://www.onlinekhabar.com/2026/11/2048268/6-0-magnitude-earthquake-strikes-off-japan-coast
- https://www.onlinekhabar.com/2026/11/2049407/thapa-withdraws-nomination-as-proposed-ambassador-to-japan
- https://www.onlinekhabar.com/2026/11/2049428/thapa-withdraws-name-from-japans-ambassadorship-says-position-is-not-the-ultimate-means-to-serve-the-people
- https://www.onlinekhabar.com/2026/11/2053923/5-7-magnitude-earthquake-hits-japan
- https://www.onlinekhabar.com/2026/11/2055109/deusi-bhailo-in-the-old-age-home
- https://www.onlinekhabar.com/2026/11/2056761/japanese-prime-minister-takaichi-to-visit-china-to-attend-apec-summit
- https://www.onlinekhabar.com/2026/11/2060864/ah-four-nights-pictures
- https://www.onlinekhabar.com/2026/11/2061169/toilets-have-changed-over-time-once-upon-a-time-feces-would-fall-from-the-wall-but-today-when-you-press-the-button-it-all-disappears
- https://www.onlinekhabar.com/2026/11/2062133/industry-minister-sah-and-japanese-ambassador-discuss-expansion-of-japanese-technology-and-investment
- https://www.onlinekhabar.com/2026/11/2062168/my-day-my-rights-my-future
- https://www.onlinekhabar.com/2026/11/2062829/kalimpong-is-not-just-about-ambo-and-dallai
- https://www.onlinekhabar.com/2026/11/2063252/why-is-lucias-entry-special-and-what-is-the-place-of-female-characters-in-the-gaming-world
- https://www.onlinekhabar.com/2026/11/2063756/are-you-frustrated-with-your-hair-not-growing-this-is-how-you-can-make-anti-dandruff-shampoo-at-home
- https://www.onlinekhabar.com/2026/11/2064068/economic-assistance-and-air-routes-are-positive-but-the-government-does-not-want-to-enter-the-border
- https://www.onlinekhabar.com/2026/11/2064292/peoples-whims-in-charming-manang
- https://www.onlinekhabar.com/2026/11/2064954/no-fees-no-homework-no-score-competition-this-is-how-education-in-the-netherlands-is
- https://www.onlinekhabar.com/2026/11/2065008/can-ai-agents-also-fall-into-temptation-anthropic-tested
- https://www.onlinekhabar.com/2026/11/2065058/police-constable-found-dead-at-baitadi-court-residence
- https://www.onlinekhabar.com/2026/11/2065093/india-provides-2-5-million-in-aid-to-palestinian-refugees
- https://www.setopati.com/blog/309033
- https://www.setopati.com/blog/350356
- https://www.setopati.com/politics/347121
- https://www.setopati.com/social/316521
- https://www.setopati.com/social/386385
- https://www.setopati.com/social/387578
- https://www.setopati.com/social/398180
- https://www.setopati.com/social/400286
