# 04 - 使用 CountDownLatch 雙門閂（起跑槍＋終點線）進行多執行緒高併發單元測試

在撰寫多執行緒併發單元測試（例如驗證 `ConcurrentLinkedDeque` 容量上限淘汰或 `AtomicLong` 序號順序）時，如果只用 `for` 迴圈呼叫 `executor.submit(...)`，會遇到兩個問題：
1. **偷跑問題（無法製造真正的瞬間高併發碰撞）**：當主執行緒還在迴圈裡建立第 2、第 3 個任務時，第 1 個執行緒早就已經跑完一大半了，執行緒之間根本沒有在「同一微秒」同時搶鎖。
2. **提早驗收問題**：`executor.submit()` 是非同步的，主執行緒派完任務後若直接往下跑 `assertThat(...)`，工作執行緒根本還沒跑完。

業界標準解法是使用 **`java.util.concurrent.CountDownLatch`（倒數門閂）** 搭配**「雙門閂模式（起跑槍 ＋ 終點線裁判）」**。

---

## 1. `CountDownLatch` 的 3 個核心動作

可以把 `CountDownLatch` 想像成**「要集滿 N 次打卡才會打開的紅綠燈擋板」**：

| API | 作用 | 口訣 / 比喻 |
|---|---|---|
| `new CountDownLatch(N)` | 建立初始值為 `N` 的倒數計數器 | 設定需要集滿 `N` 次按壓才會開門 |
| `latch.await()` | **卡在這一行等待（Blocking）**，直到計數器變成 `0` 才放行往下走（可傳入超時時間 `await(10, TimeUnit.SECONDS)`） | **「紅燈停！」**—— 誰呼叫 `await()`，誰就原地立正停在那一行 |
| `latch.countDown()` | 將計數器減 `1`（`N -> N - 1`）。當減到 `0` 的瞬間，**同時喚醒所有卡在 `await()` 的執行緒** | **「按倒數鈕！」**—— 歸零瞬間紅燈變綠燈，所有人同時放行 |

---

## 2. 雙門閂模式（起跑槍 `startLatch` ＋ 終點線 `doneLatch`）圖解

```text
【10 個工人執行緒 (選手)】                 【JUnit 主執行緒 (裁判)】
         │                                          │
  進入執行緒池就位                               把 10 個選手都排上跑道
         │                                          │
  🛑 呼叫 startLatch.await()                        │
  (全部原地立正卡在起跑線紅燈前！)                   │
         │                                   🔫 呼叫 startLatch.countDown() (1 -> 0)
         │  <────── 砰！鳴槍！綠燈亮！ ───────────  │
  🏃‍♂️ 10 個執行緒「同一微秒」同時衝出去               │
  瘋狂呼叫 store.append(...) 搶鎖碰撞！             │
         │                                   🛑 呼叫 doneLatch.await(10, SECONDS)
         │                                   (換裁判卡在終點線等 10 人跑完)
  各自跑完 120 筆後，                               │
  在 finally 呼叫 doneLatch.countDown()             │
  (10 -> 9 -> 8 ... -> 0)                           │
         │  ────── 第 10 人打卡歸零！ ───────────>  │
                                             🏁 裁判被喚醒！開始驗收測試結果
                                             assertThat(recent).hasSize(1000)
```

---

## 3. 標準範本程式碼

```java
int threadCount = 10;
int messagesPerThread = 120;

ExecutorService executor = Executors.newFixedThreadPool(threadCount);
// 1. 起跑槍：倒數 1 次就放行所有工人
CountDownLatch startLatch = new CountDownLatch(1);
// 2. 終點線計分板：要等 10 個工人都打卡完畢才放行主執行緒
CountDownLatch doneLatch = new CountDownLatch(threadCount);

for (int t = 0; t < threadCount; t++) {
    final int threadId = t;
    executor.submit(() -> {
        try {
            // 🛑 工人一進來就卡在這裡等起跑槍響
            startLatch.await();

            // 🏃‍♂️ 起跑槍響後，10 個工人同時執行這段高併發操作
            for (int i = 0; i < messagesPerThread; i++) {
                store.append(...);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // ✅ 鐵則：countDown() 一定要放在 finally！
            // 確保就算上面發生例外，計數器也會減 1，不會讓主執行緒永遠卡死
            doneLatch.countDown();
        }
    });
}

// 🔫 主執行緒把 10 個工人都派完就位後，扣下板機 (1 -> 0)，10 個工人瞬間同時開跑！
startLatch.countDown();

// 🏁 主執行緒在終點線等待 10 個工人全部跑完（最多等 10 秒避免死鎖卡死）
boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
executor.shutdown();

assertThat(finished).isTrue();
// 開始驗證併發後的資料狀態...
```

---

## 4. 必記鐵則

1. **`doneLatch.countDown()` 永遠放在 `finally` 區塊**：如果工作執行緒在執行過程中拋出 RuntimeException 而沒執行到 `countDown()`，計數器永遠不會歸零，主執行緒就會在 `doneLatch.await()` 一路卡到 Timeout。
2. **`doneLatch.await(timeout, unit)` 務必加上超時時間**：在單元測試中不要呼叫無參數的 `doneLatch.await()`，加上 `10, TimeUnit.SECONDS` 並斷言 `assertThat(finished).isTrue()`，萬一程式真的寫出死鎖（Deadlock），測試會在 10 秒後明確失敗，而不會讓整個 Maven 建置永遠掛死。
