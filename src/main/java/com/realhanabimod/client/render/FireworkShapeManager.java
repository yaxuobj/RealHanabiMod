package com.realhanabimod.client.render;

import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 花火の「形」を作るクラス。
 * hanabi.png（1粒の光）を大量に並べて球状・輪状・ハート状などの見た目を作る。
 * ここで返すのは中心から見た「単位方向ベクトル」のリスト。実際の火花はこの方向へ飛んでいく。
 */
public class FireworkShapeManager {

    public static final String[] DESIGN_NAMES = {
            "菊", "牡丹", "柳", "輪", "ハート", "星", "クロセット", "パーム"
    };

    public static int designCount() {
        return DESIGN_NAMES.length;
    }

    public static String designName(int index) {
        int i = ((index % DESIGN_NAMES.length) + DESIGN_NAMES.length) % DESIGN_NAMES.length;
        return DESIGN_NAMES[i];
    }

    private static final Random RANDOM = new Random();

    /** 火花の飛散方向（正規化ベクトル）と初速倍率のペアを返す。 */
    public static List<Spark> generate(int designIndex, long seed) {
        Random r = new Random(seed);
        int idx = ((designIndex % DESIGN_NAMES.length) + DESIGN_NAMES.length) % DESIGN_NAMES.length;
        return switch (idx) {
            case 0 -> kiku(r, 220);                        // 菊：均一な球＋放射状にまっすぐ短く尾を引く
            case 1 -> sphere(r, 260, 1.0f, 0.15f);       // 牡丹：やや乱れた球（尾を引くイメージ）
            case 2 -> willow(r, 400);
            case 3 -> ring(r, 140);                        // 輪
            case 4 -> heart(r, 160);                        // ハート
            case 5 -> star(r, 5, 150);                      // 星形
            case 6 -> sphere(r, 90, 0.6f, 0.3f);            // クロセット（親玉、小さめの球。子玉は別途爆発させる想定）
            case 7 -> palm(r, 60);                          // パーム（数本の太い筋）
            default -> sphere(r, 200, 1.0f, 0.1f);
        };
    }

    /**
     * trailScale : この火花が「尾」を引く長さの倍率。0なら尾なし（点のまま）。
     *              柳(willow)のように、玉と同じ要領で尾を伸ばしたいデザインだけ正の値を入れる。
     */
    public record Spark(Vector3f dir, float speedScale, float delaySeconds, float trailScale) {
        public Spark(Vector3f dir, float speedScale, float delaySeconds) {
            this(dir, speedScale, delaySeconds, 0f);
        }
    }

    private static List<Spark> sphere(Random r, int count, float speedBase, float jitter) {
        List<Spark> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float u = r.nextFloat() * 2f - 1f;
            float theta = r.nextFloat() * (float) (Math.PI * 2);
            float sqrt = (float) Math.sqrt(1 - u * u);
            Vector3f dir = new Vector3f(sqrt * Mth.cos(theta), u, sqrt * Mth.sin(theta));
            float speed = speedBase * (1f - jitter / 2f + r.nextFloat() * jitter);
            list.add(new Spark(dir, speed, 0f));
        }
        return list;
    }

    /**
     * 菊：均一な球状に飛び散りつつ、柳と同じ仕組みで火花1つ1つにも尾を持たせる。
     * ただし柳のように大きく垂れ下がるのではなく、写真のように「放射状にまっすぐ短く」
     * 尾を引いて消えるタイプなので、trailScaleは柳(3.2〜7.2)よりだいぶ小さい値にしている。
     * これにより、寿命(FireworkVisual側でtrailScaleから自動計算)も柳ほど長くならず、
     * 全方向にパッと開いてサラサラと短い光の筋を残しながら消えていく、菊本来の見た目になる。
     */
    private static List<Spark> kiku(Random r, int count) {
        List<Spark> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float u = r.nextFloat() * 2f - 1f;
            float theta = r.nextFloat() * (float) (Math.PI * 2);
            float sqrt = (float) Math.sqrt(Math.max(0, 1 - u * u));
            Vector3f dir = new Vector3f(sqrt * Mth.cos(theta), u, sqrt * Mth.sin(theta));

            float jitter = 0.05f;
            float speed = 1.0f * (1f - jitter / 2f + r.nextFloat() * jitter);

            // 柳(3.2〜7.2)よりずっと控えめ。放射状にまっすぐ短く尾を引く程度。
            float trailScale = 1.0f + r.nextFloat() * 1.0f;

            list.add(new Spark(dir, speed, 0f, trailScale));
        }
        return list;
    }

    private static List<Spark> willow(Random r, int count) {
        List<Spark> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 水平方向は全周
            float theta = r.nextFloat() * (float) (Math.PI * 2);

            // 上半球を中心に、やや下方向まで（-0.1 〜 1.0）広がる傘型・ドーム型のベクトル
            float u = r.nextFloat() * 1.1f - 0.1f;
            float sqrt = (float) Math.sqrt(Math.max(0, 1 - u * u));

            Vector3f dir = new Vector3f(sqrt * Mth.cos(theta), u, sqrt * Mth.sin(theta));
            if (dir.length() > 0.0001f) dir.normalize();

            // ★画像上部の「いくつかの房（ふさ）」のニュアンスをコサイン波で作る（8束ほど）
            float bunch = 1.0f + 0.3f * Mth.cos(8f * theta);

            // 初速の差を大きく取る。これにより内側で垂れる星と、外側までアーチを描く星の「層」ができる
            float speedBase = 0.4f + r.nextFloat() * 0.8f;
            float speed = speedBase * bunch;

            // ザラザラ感を出すための細かい時間差（ディレイ）
            float delay = r.nextFloat() * 0.25f;

            // ★玉と同じ「尾」を火花1つ1つにも持たせる。値が大きいほど、頭(現在位置)に対して
            //   尾の追従点が遅れて付いてくるようになり＝より長く尾を引いて垂れる。
            //   （以前の速度依存の短い尾から、4倍前後よく垂れるように引き上げ済み）
            float trailScale = 3.2f + r.nextFloat() * 4.0f;

            list.add(new Spark(dir, speed, delay, trailScale));
        }
        return list;
    }

    private static List<Spark> ring(Random r, int count) {
        List<Spark> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float theta = (float) (Math.PI * 2 * i / count);
            Vector3f dir = new Vector3f(Mth.cos(theta), (r.nextFloat() - 0.5f) * 0.05f, Mth.sin(theta));
            dir.normalize();
            list.add(new Spark(dir, 1.0f, 0f));
        }
        return list;
    }

    private static List<Spark> heart(Random r, int count) {
        List<Spark> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float t = (float) (Math.PI * 2 * i / count);
            // 2Dハート曲線を生成し、XY平面に配置（プレイヤー正面から見えるよう常にビルボードするため平面形状でOK）
            float x = 16 * Mth.sin(t) * Mth.sin(t) * Mth.sin(t);
            float y = 13 * Mth.cos(t) - 5 * Mth.cos(2 * t) - 2 * Mth.cos(3 * t) - Mth.cos(4 * t);
            Vector3f dir = new Vector3f(x, y, 0f);
            if (dir.length() > 0.0001f) dir.normalize();
            list.add(new Spark(dir, 1.0f, 0f));
        }
        return list;
    }

    private static List<Spark> star(Random r, int points, int count) {
        List<Spark> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float theta = (float) (Math.PI * 2 * i / count);
            float pulse = 0.5f + 0.5f * Mth.cos(theta * points);
            Vector3f dir = new Vector3f(Mth.cos(theta) * pulse, (r.nextFloat() - 0.5f) * 0.3f, Mth.sin(theta) * pulse);
            if (dir.length() > 0.0001f) dir.normalize();
            list.add(new Spark(dir, 0.8f + pulse * 0.4f, 0f));
        }
        return list;
    }

    private static List<Spark> palm(Random r, int strands) {
        List<Spark> list = new ArrayList<>();
        for (int s = 0; s < strands; s++) {
            float u = r.nextFloat() * 0.6f + 0.2f; // 上向き寄り
            float theta = r.nextFloat() * (float) (Math.PI * 2);
            float sqrt = (float) Math.sqrt(Math.max(0, 1 - u * u));
            Vector3f dir = new Vector3f(sqrt * Mth.cos(theta), u, sqrt * Mth.sin(theta));
            dir.normalize();
            // 筋の途中にも粒を置いて「太い光の筋」に見せる
            for (int j = 1; j <= 6; j++) {
                list.add(new Spark(new Vector3f(dir), 1.2f * j / 6f, 0f));
            }
        }
        return list;
    }
}