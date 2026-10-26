// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import de.schliweb.makeacopy.ml.docquad.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class Expected {
  public static void main(String[] args) throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    String line;
    while ((line = in.readLine()) != null) {
      String[] p = line.trim().split(" ");
      int w = Integer.parseInt(p[1]), h = Integer.parseInt(p[2]);
      FloatBuffer fb = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0], p[0] + ".bin")))
          .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
      float[][][][] hm = new float[1][4][64][64];
      float[][][][] mask = new float[1][1][64][64];
      for (int c = 0; c < 4; c++) for (int y = 0; y < 64; y++) fb.get(hm[0][c][y]);
      for (int y = 0; y < 64; y++) fb.get(mask[0][0][y]);
      DocQuadLetterbox lb = DocQuadLetterbox.create(w, h, 256, 256);
      DocQuadPostprocessor.Result r = DocQuadPostprocessor.postprocess(
          hm, mask, lb, DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC);
      StringBuilder sb = new StringBuilder(p[0]).append(' ').append(w).append(' ').append(h);
      sb.append(' ').append(r.chosenSource()).append(' ').append(r.quadFromMaskUsedFallback());
      sb.append(' ').append(isValidQuad(r.chosenQuadOriginal(), w, h));
      sb.append(' ').append(isValidQuad(r.cornersOriginal(), w, h));
      sb.append(' ').append(r.minPeakProb()).append(' ').append(r.cornerMaskIou());
      for (double[][] q : new double[][][] {r.chosenQuadOriginal(), r.cornersOriginal()})
        for (double[] pt : q) sb.append(' ').append(pt[0]).append(' ').append(pt[1]);
      System.out.println(sb);
    }
  }

  static boolean isValidQuad(double[][] c, int w, int h) {
    for (int i = 0; i < 4; i++) {
      double x = c[i][0], y = c[i][1];
      if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
      if (x < -w * 0.25 || x > w * 1.25) return false;
      if (y < -h * 0.25 || y > h * 1.25) return false;
    }
    double prevSign = 0.0;
    for (int i = 0; i < 4; i++) {
      double[] a = c[i], b = c[(i + 1) % 4], d = c[(i + 2) % 4];
      double cross = (b[0] - a[0]) * (d[1] - b[1]) - (b[1] - a[1]) * (d[0] - b[0]);
      if (!Double.isFinite(cross) || cross == 0.0) return false;
      double sign = Math.signum(cross);
      if (i == 0) { if (sign < 0.0) return false; prevSign = sign; }
      else if (sign != prevSign) return false;
    }
    return true;
  }
}
