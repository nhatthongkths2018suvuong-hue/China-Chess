package com.cotuong.engine;

import java.util.Arrays;
import java.util.Random;

/**
 * Thế cờ tướng: bàn cờ 10 hàng x 9 cột, ô = hàng*9 + cột.
 * Hàng 0 ở trên cùng (phía quân Đen), hàng 9 ở dưới cùng (phía quân Đỏ).
 * Quân Đỏ là số dương, quân Đen là số âm. Loại quân: xem các hằng số bên dưới.
 * Nước đi được mã hóa thành int: từ | (đến << 8).
 */
public final class Position {
    public static final int KING = 1, ADVISOR = 2, BISHOP = 3, KNIGHT = 4, ROOK = 5, CANNON = 6, PAWN = 7;

    public static final int[] ROW = new int[90];
    public static final int[] COL = new int[90];
    private static final long[][] Z = new long[15][90];
    private static final long SIDE;
    private static final int[] DR = {-1, 1, 0, 0};
    private static final int[] DC = {0, 0, -1, 1};
    private static final int STACK = 2048;

    static {
        for (int i = 0; i < 90; i++) {
            ROW[i] = i / 9;
            COL[i] = i % 9;
        }
        Random r = new Random(20240607L);
        for (int p = 0; p < 15; p++) for (int s = 0; s < 90; s++) Z[p][s] = r.nextLong();
        SIDE = r.nextLong();
    }

    public final int[] sq = new int[90];
    public boolean redToMove = true;
    public final int[] kingSq = new int[2]; // 0 = Đỏ, 1 = Đen
    public long hash;
    public int sp = 0; // số nước đã đi

    private final int[] moveStack = new int[STACK];
    private final int[] capStack = new int[STACK];
    private final long[] hashStack = new long[STACK];
    private final int[] tmpGen = new int[200];

    public Position() {
        reset();
    }

    public void reset() {
        Arrays.fill(sq, 0);
        int[] back = {ROOK, KNIGHT, BISHOP, ADVISOR, KING, ADVISOR, BISHOP, KNIGHT, ROOK};
        for (int c = 0; c < 9; c++) {
            sq[c] = -back[c];
            sq[81 + c] = back[c];
        }
        sq[2 * 9 + 1] = -CANNON;
        sq[2 * 9 + 7] = -CANNON;
        sq[7 * 9 + 1] = CANNON;
        sq[7 * 9 + 7] = CANNON;
        for (int c = 0; c < 9; c += 2) {
            sq[3 * 9 + c] = -PAWN;
            sq[6 * 9 + c] = PAWN;
        }
        kingSq[0] = 81 + 4;
        kingSq[1] = 4;
        redToMove = true;
        sp = 0;
        hash = 0;
        for (int s = 0; s < 90; s++) if (sq[s] != 0) hash ^= Z[sq[s] + 7][s];
    }

    public Position copy() {
        Position p = new Position();
        System.arraycopy(sq, 0, p.sq, 0, 90);
        p.redToMove = redToMove;
        p.kingSq[0] = kingSq[0];
        p.kingSq[1] = kingSq[1];
        p.hash = hash;
        p.sp = sp;
        System.arraycopy(moveStack, 0, p.moveStack, 0, sp);
        System.arraycopy(capStack, 0, p.capStack, 0, sp);
        System.arraycopy(hashStack, 0, p.hashStack, 0, sp);
        return p;
    }

    public void makeMove(int m) {
        int from = m & 255, to = m >> 8;
        int p = sq[from], cap = sq[to];
        moveStack[sp] = m;
        capStack[sp] = cap;
        hashStack[sp] = hash;
        sp++;
        sq[to] = p;
        sq[from] = 0;
        hash ^= Z[p + 7][from] ^ Z[p + 7][to] ^ SIDE;
        if (cap != 0) hash ^= Z[cap + 7][to];
        if (p == KING) kingSq[0] = to;
        else if (p == -KING) kingSq[1] = to;
        redToMove = !redToMove;
    }

    public void undoMove() {
        sp--;
        int m = moveStack[sp];
        int from = m & 255, to = m >> 8;
        int p = sq[to];
        sq[from] = p;
        sq[to] = capStack[sp];
        hash = hashStack[sp];
        if (p == KING) kingSq[0] = from;
        else if (p == -KING) kingSq[1] = from;
        redToMove = !redToMove;
    }

    /** Số lần thế cờ hiện tại đã xuất hiện trước đó (cùng bên đi). */
    public int repetitions() {
        int c = 0;
        for (int i = sp - 2; i >= 0; i -= 2) if (hashStack[i] == hash) c++;
        return c;
    }

    private static boolean inBoard(int r, int c) {
        return r >= 0 && r < 10 && c >= 0 && c < 9;
    }

    private static boolean inPalace(int r, int c, int side) {
        return c >= 3 && c <= 5 && (side > 0 ? (r >= 7 && r <= 9) : (r >= 0 && r <= 2));
    }

    private int add(int[] mv, int n, int from, int to, int side, boolean caps) {
        int t = sq[to];
        if (t * side > 0) return n;
        if (caps && t == 0) return n;
        mv[n] = from | (to << 8);
        return n + 1;
    }

    /** Sinh nước đi "gần hợp lệ" (chưa loại nước để tướng bị chiếu). */
    public int genMoves(int[] mv, boolean caps) {
        int n = 0;
        int side = redToMove ? 1 : -1;
        for (int s = 0; s < 90; s++) {
            int p = sq[s];
            if (p * side <= 0) continue;
            int type = p > 0 ? p : -p;
            int r = ROW[s], c = COL[s];
            switch (type) {
                case KING:
                    for (int d = 0; d < 4; d++) {
                        int nr = r + DR[d], nc = c + DC[d];
                        if (inPalace(nr, nc, side)) n = add(mv, n, s, nr * 9 + nc, side, caps);
                    }
                    break;
                case ADVISOR:
                    for (int dr = -1; dr <= 1; dr += 2)
                        for (int dc = -1; dc <= 1; dc += 2) {
                            int nr = r + dr, nc = c + dc;
                            if (inPalace(nr, nc, side)) n = add(mv, n, s, nr * 9 + nc, side, caps);
                        }
                    break;
                case BISHOP:
                    for (int dr = -1; dr <= 1; dr += 2)
                        for (int dc = -1; dc <= 1; dc += 2) {
                            int nr = r + 2 * dr, nc = c + 2 * dc;
                            if (!inBoard(nr, nc)) continue;
                            if (side > 0 ? nr < 5 : nr > 4) continue; // không qua sông
                            if (sq[(r + dr) * 9 + (c + dc)] != 0) continue; // cản mắt tượng
                            n = add(mv, n, s, nr * 9 + nc, side, caps);
                        }
                    break;
                case KNIGHT:
                    for (int d = 0; d < 4; d++) {
                        int lr = r + DR[d], lc = c + DC[d];
                        if (!inBoard(lr, lc) || sq[lr * 9 + lc] != 0) continue; // cản chân mã
                        for (int k = -1; k <= 1; k += 2) {
                            int tr, tc;
                            if (DR[d] != 0) {
                                tr = r + 2 * DR[d];
                                tc = c + k;
                            } else {
                                tr = r + k;
                                tc = c + 2 * DC[d];
                            }
                            if (inBoard(tr, tc)) n = add(mv, n, s, tr * 9 + tc, side, caps);
                        }
                    }
                    break;
                case ROOK:
                    for (int d = 0; d < 4; d++) {
                        int nr = r + DR[d], nc = c + DC[d];
                        while (inBoard(nr, nc)) {
                            int t = sq[nr * 9 + nc];
                            if (t == 0) {
                                if (!caps) { mv[n++] = s | ((nr * 9 + nc) << 8); }
                            } else {
                                if (t * side < 0) mv[n++] = s | ((nr * 9 + nc) << 8);
                                break;
                            }
                            nr += DR[d];
                            nc += DC[d];
                        }
                    }
                    break;
                case CANNON:
                    for (int d = 0; d < 4; d++) {
                        int nr = r + DR[d], nc = c + DC[d];
                        while (inBoard(nr, nc) && sq[nr * 9 + nc] == 0) {
                            if (!caps) mv[n++] = s | ((nr * 9 + nc) << 8);
                            nr += DR[d];
                            nc += DC[d];
                        }
                        nr += DR[d];
                        nc += DC[d]; // bỏ qua quân làm "ngòi"
                        while (inBoard(nr, nc)) {
                            int t = sq[nr * 9 + nc];
                            if (t != 0) {
                                if (t * side < 0) mv[n++] = s | ((nr * 9 + nc) << 8);
                                break;
                            }
                            nr += DR[d];
                            nc += DC[d];
                        }
                    }
                    break;
                case PAWN: {
                    int dr = side > 0 ? -1 : 1;
                    if (inBoard(r + dr, c)) n = add(mv, n, s, (r + dr) * 9 + c, side, caps);
                    boolean crossed = side > 0 ? r <= 4 : r >= 5;
                    if (crossed) {
                        if (c > 0) n = add(mv, n, s, r * 9 + c - 1, side, caps);
                        if (c < 8) n = add(mv, n, s, r * 9 + c + 1, side, caps);
                    }
                    break;
                }
            }
        }
        return n;
    }

    /** Tướng của bên 'red' có đang bị chiếu không (kể cả hai tướng đối mặt). */
    public boolean inCheck(boolean red) {
        int side = red ? 1 : -1;
        int k = kingSq[red ? 0 : 1];
        int kr = ROW[k], kc = COL[k];
        int ok = kingSq[red ? 1 : 0];
        if (COL[ok] == kc) { // tướng đối mặt
            int lo = Math.min(kr, ROW[ok]), hi = Math.max(kr, ROW[ok]);
            boolean clear = true;
            for (int r = lo + 1; r < hi; r++) if (sq[r * 9 + kc] != 0) { clear = false; break; }
            if (clear) return true;
        }
        for (int d = 0; d < 4; d++) { // xe và pháo
            int r = kr + DR[d], c = kc + DC[d];
            boolean screen = false;
            while (inBoard(r, c)) {
                int t = sq[r * 9 + c];
                if (t != 0) {
                    if (!screen) {
                        if (t == -side * ROOK) return true;
                        screen = true;
                    } else {
                        if (t == -side * CANNON) return true;
                        break;
                    }
                }
                r += DR[d];
                c += DC[d];
            }
        }
        for (int a = -2; a <= 2; a++) { // mã
            if (a == 0) continue;
            for (int b = -2; b <= 2; b++) {
                if (b == 0 || Math.abs(a) == Math.abs(b)) continue;
                int nr = kr + a, nc = kc + b;
                if (!inBoard(nr, nc) || sq[nr * 9 + nc] != -side * KNIGHT) continue;
                int lr, lc;
                if (Math.abs(a) == 2) { lr = kr + a / 2; lc = kc + b; }
                else { lr = kr + a; lc = kc + b / 2; }
                if (sq[lr * 9 + lc] == 0) return true;
            }
        }
        int pawn = -side * PAWN; // tốt
        if (inBoard(kr - side, kc) && sq[(kr - side) * 9 + kc] == pawn) return true;
        if (kc > 0 && sq[kr * 9 + kc - 1] == pawn) return true;
        if (kc < 8 && sq[kr * 9 + kc + 1] == pawn) return true;
        return false;
    }

    /** Sinh các nước đi hợp lệ hoàn toàn. */
    public int genLegal(int[] out) {
        int cnt = genMoves(tmpGen, false);
        boolean red = redToMove;
        int n = 0;
        for (int i = 0; i < cnt; i++) {
            makeMove(tmpGen[i]);
            boolean bad = inCheck(red);
            undoMove();
            if (!bad) out[n++] = tmpGen[i];
        }
        return n;
    }

    /** Các ô đến hợp lệ của quân đang đứng ở ô 'from'. */
    public int legalDestinations(int from, int[] out) {
        int cnt = genMoves(tmpGen, false);
        boolean red = redToMove;
        int n = 0;
        for (int i = 0; i < cnt; i++) {
            if ((tmpGen[i] & 255) != from) continue;
            makeMove(tmpGen[i]);
            boolean bad = inCheck(red);
            undoMove();
            if (!bad && n < out.length) out[n++] = tmpGen[i] >> 8;
        }
        return n;
    }
}
