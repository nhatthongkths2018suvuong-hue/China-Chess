package com.cotuong.engine;

import java.util.Arrays;
import java.util.Random;

/**
 * Máy cờ: negamax + alpha-beta, đào sâu dần (iterative deepening), PVS, bảng băm,
 * bỏ lượt thử (null move), giảm độ sâu nước muộn (LMR), nước sát thủ (killer), lịch sử (history),
 * tìm kiếm tĩnh (quiescence). Viết thuần Java, không dùng thư viện native.
 */
public final class Engine {
    public static final int INF = 32000, MATE = 30000;
    private static final int MAXPLY = 64;
    private static final int[] VAL = Position.VAL;

    private static final int TT_SIZE = 1 << 18, TT_MASK = TT_SIZE - 1;
    private static final int EXACT = 0, LOWER = 1, UPPER = 2;
    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttMove = new int[TT_SIZE];
    private final short[] ttScore = new short[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];

    private final int[][] moves = new int[MAXPLY][200];
    private final int[][] scores = new int[MAXPLY][200];
    private final int[][] killers = new int[MAXPLY][2];
    private final int[] history = new int[90 * 90];
    private final Random rnd = new Random();
    private static final long[] QKEY = new long[256];
    static {
        Random r = new Random(777);
        for (int i = 0; i < QKEY.length; i++) QKEY[i] = r.nextLong();
    }

    // Luật hòa: số nước liên tiếp không ăn quân thì hòa (0 = máy không xét luật này).
    private int noCaptureLimit = 0;

    public void setNoCaptureLimit(int plies) {
        noCaptureLimit = plies;
    }

    private Position pos;
    private long deadline;
    private boolean abort;
    private long nodes;

    /** Trả về nước đi tốt nhất của bên đang đến lượt (0 nếu không còn nước đi). */
    public int think(Position p, int maxDepth, long timeMs, int noise) {
        pos = p;
        long start = System.currentTimeMillis();
        deadline = timeMs > 0 ? start + timeMs : Long.MAX_VALUE; // 0 = không giới hạn thời gian
        abort = false;
        nodes = 0;
        Arrays.fill(history, 0);
        for (int i = 0; i < MAXPLY; i++) killers[i][0] = killers[i][1] = 0;

        int[] root = new int[200];
        int n = pos.genLegal(root);
        if (n == 0) return 0;
        if (n == 1) return root[0];
        int best = root[0];

        if (noise > 0) { // mức dễ: tính từng nước ở độ sâu cố định rồi cộng nhiễu ngẫu nhiên
            int bestScore = -INF * 2;
            for (int i = 0; i < n; i++) {
                pos.makeMove(root[i]);
                int sc = -search(maxDepth - 1, -INF, INF, 1, true);
                pos.undoMove();
                if (abort) break; // hết giờ: dùng nước tốt nhất đã tính được
                sc += rnd.nextInt(2 * noise + 1) - noise;
                if (sc > bestScore) {
                    bestScore = sc;
                    best = root[i];
                }
            }
            return best;
        }

        // sắp xếp sơ bộ: ăn quân trước
        int[] rs = new int[n];
        for (int i = 0; i < n; i++) rs[i] = moveScore(root[i], 0, 0);
        for (int i = 1; i < n; i++) {
            int m = root[i], s = rs[i], j = i - 1;
            while (j >= 0 && rs[j] < s) { root[j + 1] = root[j]; rs[j + 1] = rs[j]; j--; }
            root[j + 1] = m;
            rs[j + 1] = s;
        }
        best = root[0];

        for (int d = 1; d <= maxDepth; d++) {
            int alpha = -INF, bm = root[0], searched = 0;
            for (int i = 0; i < n; i++) {
                pos.makeMove(root[i]);
                int sc;
                if (pos.repetitions() > 0) {
                    sc = 0;
                } else if (i == 0) {
                    sc = -search(d - 1, -INF, INF, 1, true);
                } else {
                    sc = -search(d - 1, -alpha - 1, -alpha, 1, true);
                    if (sc > alpha && !abort) sc = -search(d - 1, -INF, -alpha, 1, true);
                }
                pos.undoMove();
                if (abort) break;
                searched++;
                if (i == 0 || sc > alpha) {
                    alpha = sc;
                    bm = root[i];
                }
            }
            if (abort) {
                if (searched >= 1) best = bm; // kết quả dở dang vẫn dùng được: nước tốt nhất trong số đã tính xong
                break;
            }
            best = bm;
            for (int i = 0; i < n; i++) { // đưa nước tốt nhất lên đầu cho vòng sau
                if (root[i] == bm) {
                    System.arraycopy(root, 0, root, 1, i);
                    root[0] = bm;
                    break;
                }
            }
            if (alpha > MATE - 100) break;
            if (timeMs > 0 && System.currentTimeMillis() - start > timeMs * 2 / 5) break;
        }
        return best;
    }

    private int evaluate() {
        int[] c = pos.cnt;
        int s = pos.score;
        int redAtk = 3 * c[7 + Position.ROOK] + 2 * c[7 + Position.CANNON] + 2 * c[7 + Position.KNIGHT];
        int blkAtk = 3 * c[7 - Position.ROOK] + 2 * c[7 - Position.CANNON] + 2 * c[7 - Position.KNIGHT];
        int redDef = c[7 + Position.ADVISOR] + c[7 + Position.BISHOP];
        int blkDef = c[7 - Position.ADVISOR] + c[7 - Position.BISHOP];
        // lực tấn công càng mạnh khi phòng thủ (sĩ, tượng) của đối phương càng mỏng
        s += (redAtk * (4 - blkDef) - blkAtk * (4 - redDef)) * 2;
        if (noCaptureLimit > 0) {
            // ở 30% cuối của giới hạn, lợi thế "tan" dần về hòa: bên hơn phải tìm cách ăn quân, bên kém cố kéo hòa
            int q = pos.quiet, half = noCaptureLimit * 7 / 10;
            if (q >= noCaptureLimit) s = 0;
            else if (q > half) s = s * (noCaptureLimit - q) / (noCaptureLimit - half);
        }
        return (pos.redToMove ? s : -s) + 10;
    }

    private int moveScore(int m, int ttm, int ply) {
        if (m == ttm) return 1000000;
        int victim = pos.sq[m >> 8];
        if (victim != 0) {
            int v = VAL[victim > 0 ? victim : -victim];
            int a = pos.sq[m & 255];
            return 100000 + v * 8 - VAL[a > 0 ? a : -a];
        }
        if (m == killers[ply][0]) return 90000;
        if (m == killers[ply][1]) return 89000;
        return history[(m & 255) * 90 + (m >> 8)];
    }

    private int search(int depth, int alpha, int beta, int ply, boolean allowNull) {
        if ((++nodes & 1023) == 0 && System.currentTimeMillis() > deadline) abort = true;
        if (abort) return 0;
        if (ply >= MAXPLY - 2) return evaluate();
        if (pos.repetitions() > 0) return 0;

        boolean red = pos.redToMove;
        if (noCaptureLimit > 0 && pos.quiet >= noCaptureLimit) {
            // đủ giới hạn: hòa, trừ khi bên đến lượt đã bị chiếu bí (khi đó bên đi nước vừa rồi thắng)
            int[] m0 = moves[ply];
            int n0 = pos.genMoves(m0, false);
            for (int i = 0; i < n0; i++) {
                pos.makeMove(m0[i]);
                boolean bad = pos.inCheck(red);
                pos.undoMove();
                if (!bad) return 0;
            }
            return -(MATE - ply);
        }
        boolean inChk = pos.inCheck(red);
        if (inChk && ply < 40) depth++;
        if (depth <= 0) return quiesce(alpha, beta, ply);
        boolean pv = beta - alpha > 1;

        long key = pos.hash;
        if (noCaptureLimit > 0 && pos.quiet > noCaptureLimit / 2) key ^= QKEY[pos.quiet & 255]; // kết quả phụ thuộc bộ đếm
        int idx = (int) (key & TT_MASK);
        int ttm = 0;
        if (ttKey[idx] == key) {
            ttm = ttMove[idx];
            if (ttDepth[idx] >= depth) {
                int s = ttScore[idx];
                if (s > MATE - 100) s -= ply; else if (s < -MATE + 100) s += ply;
                int f = ttFlag[idx];
                if (!pv || f == EXACT) {
                    if (f == EXACT) return s;
                    if (f == LOWER && s > alpha) alpha = s;
                    else if (f == UPPER && s < beta) beta = s;
                    if (alpha >= beta) return s;
                }
            }
        }

        // bỏ lượt thử: nếu bỏ lượt mà vẫn >= beta thì cắt luôn
        if (allowNull && !pv && !inChk && depth >= 3 && beta < MATE - 100) {
            int[] c = pos.cnt;
            int atk = red ? c[7 + Position.ROOK] + c[7 + Position.CANNON] + c[7 + Position.KNIGHT]
                    : c[7 - Position.ROOK] + c[7 - Position.CANNON] + c[7 - Position.KNIGHT];
            if (atk >= 2 && evaluate() >= beta) {
                pos.makeNull();
                int v = -search(depth - (depth >= 6 ? 4 : 3), -beta, -beta + 1, ply + 1, false);
                pos.undoNull();
                if (abort) return 0;
                if (v >= beta) return v > MATE - 100 ? beta : v;
            }
        }

        int[] mv = moves[ply], sc = scores[ply];
        int n = pos.genMoves(mv, false);
        for (int i = 0; i < n; i++) sc[i] = moveScore(mv[i], ttm, ply);

        int best = -INF, bestMove = 0, origAlpha = alpha, legal = 0;
        for (int i = 0; i < n; i++) {
            int bi = i;
            for (int j = i + 1; j < n; j++) if (sc[j] > sc[bi]) bi = j;
            int m = mv[bi]; mv[bi] = mv[i]; mv[i] = m;
            int t = sc[bi]; sc[bi] = sc[i]; sc[i] = t;

            boolean capture = pos.sq[m >> 8] != 0;
            pos.makeMove(m);
            if (pos.inCheck(red)) { pos.undoMove(); continue; }
            legal++;
            int v;
            if (legal == 1) {
                v = -search(depth - 1, -beta, -alpha, ply + 1, true);
            } else {
                int reduce = 0;
                if (depth >= 3 && legal > 4 && !capture && !inChk) reduce = (depth >= 6 && legal > 10) ? 2 : 1;
                v = -search(depth - 1 - reduce, -alpha - 1, -alpha, ply + 1, true);
                if (v > alpha && reduce > 0 && !abort) v = -search(depth - 1, -alpha - 1, -alpha, ply + 1, true);
                if (v > alpha && v < beta && !abort) v = -search(depth - 1, -beta, -alpha, ply + 1, true);
            }
            pos.undoMove();
            if (abort) return 0;
            if (v > best) {
                best = v;
                bestMove = m;
                if (v > alpha) {
                    alpha = v;
                    if (alpha >= beta) {
                        if (!capture) {
                            if (killers[ply][0] != m) { killers[ply][1] = killers[ply][0]; killers[ply][0] = m; }
                            int h = (m & 255) * 90 + (m >> 8);
                            history[h] = Math.min(80000, history[h] + depth * depth);
                        }
                        break;
                    }
                }
            }
        }
        if (legal == 0) return -(MATE - ply); // hết nước đi = thua

        int s = best;
        if (s > MATE - 100) s += ply; else if (s < -MATE + 100) s -= ply;
        ttKey[idx] = key;
        ttMove[idx] = bestMove;
        ttScore[idx] = (short) s;
        ttDepth[idx] = (byte) depth;
        ttFlag[idx] = (byte) (best <= origAlpha ? UPPER : (best >= beta ? LOWER : EXACT));
        return best;
    }

    private int quiesce(int alpha, int beta, int ply) {
        if ((++nodes & 1023) == 0 && System.currentTimeMillis() > deadline) abort = true;
        if (abort) return 0;
        int stand = evaluate();
        if (ply >= MAXPLY - 2 || stand >= beta) return stand;
        if (stand > alpha) alpha = stand;

        boolean red = pos.redToMove;
        int[] mv = moves[ply], sc = scores[ply];
        int n = pos.genMoves(mv, true);
        for (int i = 0; i < n; i++) sc[i] = moveScore(mv[i], 0, ply);
        for (int i = 0; i < n; i++) {
            int bi = i;
            for (int j = i + 1; j < n; j++) if (sc[j] > sc[bi]) bi = j;
            int m = mv[bi]; mv[bi] = mv[i]; mv[i] = m;
            int t = sc[bi]; sc[bi] = sc[i]; sc[i] = t;

            pos.makeMove(m);
            if (pos.inCheck(red)) { pos.undoMove(); continue; }
            int v = -quiesce(-beta, -alpha, ply + 1);
            pos.undoMove();
            if (abort) return 0;
            if (v >= beta) return v;
            if (v > alpha) alpha = v;
        }
        return alpha;
    }
}
