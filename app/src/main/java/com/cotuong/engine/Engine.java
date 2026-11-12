package com.cotuong.engine;

import java.util.Random;

/**
 * Máy cờ: negamax + alpha-beta, đào sâu dần (iterative deepening),
 * bảng băm (transposition table), tìm kiếm tĩnh (quiescence), sắp xếp nước đi.
 * Viết thuần Java, không dùng thư viện native.
 */
public final class Engine {
    public static final int INF = 32000, MATE = 30000;
    private static final int[] VAL = {0, 0, 20, 20, 45, 100, 50, 10};

    private static final int TT_SIZE = 1 << 17, TT_MASK = TT_SIZE - 1;
    private static final int EXACT = 0, LOWER = 1, UPPER = 2;
    private final long[] ttKey = new long[TT_SIZE];
    private final int[] ttMove = new int[TT_SIZE];
    private final short[] ttScore = new short[TT_SIZE];
    private final byte[] ttDepth = new byte[TT_SIZE];
    private final byte[] ttFlag = new byte[TT_SIZE];

    private final int[][] moves = new int[64][200];
    private final int[][] scores = new int[64][200];
    private final int[] history = new int[90 * 90];
    private final Random rnd = new Random();

    private Position pos;
    private long deadline;
    private boolean abort;
    private long nodes;

    /** Trả về nước đi tốt nhất của bên đang đến lượt (0 nếu không còn nước đi). */
    public int think(Position p, int maxDepth, long timeMs, int noise) {
        pos = p;
        long start = System.currentTimeMillis();
        deadline = start + timeMs;
        abort = false;
        nodes = 0;
        java.util.Arrays.fill(history, 0);

        int[] root = new int[200];
        int n = pos.genLegal(root);
        if (n == 0) return 0;
        if (n == 1) return root[0];
        int best = root[0];

        if (noise > 0) { // mức dễ: tính từng nước ở độ sâu cố định rồi cộng nhiễu ngẫu nhiên
            int bestScore = -INF * 2;
            for (int i = 0; i < n; i++) {
                pos.makeMove(root[i]);
                int sc = -search(maxDepth - 1, -INF, INF, 1);
                pos.undoMove();
                sc += rnd.nextInt(2 * noise + 1) - noise;
                if (sc > bestScore) {
                    bestScore = sc;
                    best = root[i];
                }
            }
            return best;
        }

        for (int d = 1; d <= maxDepth; d++) {
            int alpha = -INF, bm = root[0];
            for (int i = 0; i < n; i++) {
                pos.makeMove(root[i]);
                int sc = pos.repetitions() > 0 ? 0 : -search(d - 1, -INF, -alpha, 1);
                pos.undoMove();
                if (abort) break;
                if (i == 0 || sc > alpha) {
                    alpha = sc;
                    bm = root[i];
                }
            }
            if (abort) break;
            best = bm;
            for (int i = 0; i < n; i++) { // đưa nước tốt nhất lên đầu cho vòng sau
                if (root[i] == bm) {
                    System.arraycopy(root, 0, root, 1, i);
                    root[0] = bm;
                    break;
                }
            }
            if (alpha > MATE - 100) break;
            if (System.currentTimeMillis() - start > timeMs / 2) break;
        }
        return best;
    }

    private int evaluate() {
        int[] sq = pos.sq;
        int score = 0;
        for (int s = 0; s < 90; s++) {
            int p = sq[s];
            if (p == 0) continue;
            int type = p > 0 ? p : -p;
            int r = Position.ROW[s], c = Position.COL[s];
            int v = VAL[type];
            int cen = 4 - Math.abs(c - 4);
            switch (type) {
                case Position.KNIGHT: v += cen * 3; break;
                case Position.CANNON: v += cen; break;
                case Position.PAWN:
                    if (p > 0) { if (r <= 4) v += 15 + (4 - r) * 4; }
                    else { if (r >= 5) v += 15 + (r - 5) * 4; }
                    break;
                default: break;
            }
            score += p > 0 ? v : -v;
        }
        return pos.redToMove ? score : -score;
    }

    private int moveScore(int m, int ttm) {
        if (m == ttm) return 1000000;
        int victim = pos.sq[m >> 8];
        if (victim != 0) {
            int v = VAL[victim > 0 ? victim : -victim];
            int a = pos.sq[m & 255];
            return 10000 + v * 16 - VAL[a > 0 ? a : -a];
        }
        return history[(m & 255) * 90 + (m >> 8)];
    }

    private int search(int depth, int alpha, int beta, int ply) {
        if ((++nodes & 2047) == 0 && System.currentTimeMillis() > deadline) abort = true;
        if (abort) return 0;
        if (ply >= 60) return evaluate();
        if (pos.repetitions() > 0) return 0;

        boolean red = pos.redToMove;
        if (pos.inCheck(red)) depth++;
        if (depth <= 0) return quiesce(alpha, beta, ply);

        int idx = (int) (pos.hash & TT_MASK);
        int ttm = 0;
        if (ttKey[idx] == pos.hash) {
            ttm = ttMove[idx];
            if (ttDepth[idx] >= depth) {
                int s = ttScore[idx];
                if (s > MATE - 100) s -= ply; else if (s < -MATE + 100) s += ply;
                int f = ttFlag[idx];
                if (f == EXACT) return s;
                if (f == LOWER && s > alpha) alpha = s;
                else if (f == UPPER && s < beta) beta = s;
                if (alpha >= beta) return s;
            }
        }

        int[] mv = moves[ply], sc = scores[ply];
        int n = pos.genMoves(mv, false);
        for (int i = 0; i < n; i++) sc[i] = moveScore(mv[i], ttm);

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
            int v = -search(depth - 1, -beta, -alpha, ply + 1);
            pos.undoMove();
            if (abort) return 0;
            if (v > best) {
                best = v;
                bestMove = m;
                if (v > alpha) {
                    alpha = v;
                    if (alpha >= beta) {
                        if (!capture) history[(m & 255) * 90 + (m >> 8)] += depth * depth;
                        break;
                    }
                }
            }
        }
        if (legal == 0) return -(MATE - ply); // hết nước đi = thua

        int s = best;
        if (s > MATE - 100) s += ply; else if (s < -MATE + 100) s -= ply;
        ttKey[idx] = pos.hash;
        ttMove[idx] = bestMove;
        ttScore[idx] = (short) s;
        ttDepth[idx] = (byte) depth;
        ttFlag[idx] = (byte) (best <= origAlpha ? UPPER : (best >= beta ? LOWER : EXACT));
        return best;
    }

    private int quiesce(int alpha, int beta, int ply) {
        if ((++nodes & 2047) == 0 && System.currentTimeMillis() > deadline) abort = true;
        if (abort) return 0;
        int stand = evaluate();
        if (ply >= 60 || stand >= beta) return stand;
        if (stand > alpha) alpha = stand;

        boolean red = pos.redToMove;
        int[] mv = moves[ply], sc = scores[ply];
        int n = pos.genMoves(mv, true);
        for (int i = 0; i < n; i++) sc[i] = moveScore(mv[i], 0);
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
