/**
 * jsdom SVG 几何最小 polyfill（REQ-20261124-224523 delta-r2 边标签渲染面用例依赖）：
 * - SVGSVGElement.createSVGMatrix/Point/Transform(FromMatrix)：x6-common matrix.ts 依赖；
 * - SVGPathElement.getTotalLength/getPointAtLength：X6 边标签定位（updateLabelPositions）依赖；
 * - SVGGraphicsElement.getBBox / SVGTextElement.getComputedTextLength：X6 文本 attr 依赖。
 * 实现按标准仿射矩阵语义（[[a c e],[b d f],[0 0 1]]），路径按 d 属性分段线性近似；
 * 只补环境缺口，不改变断言语义（断言对象=X6 真实渲染管线的 DOM 产出）。
 */
interface AffineMatrix {
  a: number; b: number; c: number; d: number; e: number; f: number;
  multiply(o: AffineMatrix): AffineMatrix;
  translate(tx: number, ty: number): AffineMatrix;
  scale(s: number, sy?: number): AffineMatrix;
  scaleNonUniform(sx: number, sy: number): AffineMatrix;
  rotate(deg: number): AffineMatrix;
  rotateFromVector(x: number, y: number): AffineMatrix;
  skewX(deg: number): AffineMatrix;
  skewY(deg: number): AffineMatrix;
  flipX(): AffineMatrix;
  flipY(): AffineMatrix;
  inverse(): AffineMatrix;
}

const RAD = Math.PI / 180;

function newMatrix(a = 1, b = 0, c = 0, d = 1, e = 0, f = 0): AffineMatrix {
  return {
    a, b, c, d, e, f,
    multiply(o) {
      return newMatrix(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.e + c * o.f + e,
        b * o.e + d * o.f + f,
      );
    },
    translate(tx, ty) { return this.multiply(newMatrix(1, 0, 0, 1, tx, ty)); },
    scale(s, sy) { return this.scaleNonUniform(s, sy ?? s); },
    scaleNonUniform(sx, sy) { return this.multiply(newMatrix(sx, 0, 0, sy, 0, 0)); },
    rotate(deg) {
      const cos = Math.cos(deg * RAD);
      const sin = Math.sin(deg * RAD);
      return this.multiply(newMatrix(cos, sin, -sin, cos, 0, 0));
    },
    rotateFromVector(x, y) { return this.rotate(Math.atan2(y, x) / RAD); },
    skewX(deg) { return this.multiply(newMatrix(1, 0, Math.tan(deg * RAD), 1, 0, 0)); },
    skewY(deg) { return this.multiply(newMatrix(1, Math.tan(deg * RAD), 0, 1, 0, 0)); },
    flipX() { return this.multiply(newMatrix(-1, 0, 0, 1, 0, 0)); },
    flipY() { return this.multiply(newMatrix(1, 0, 0, -1, 0, 0)); },
    inverse() {
      const det = a * d - b * c;
      return newMatrix(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det);
    },
  };
}

function newPoint(x = 0, y = 0): { x: number; y: number; matrixTransform(m: AffineMatrix): { x: number; y: number } } {
  return {
    x, y,
    matrixTransform(m) {
      return { x: m.a * x + m.c * y + m.e, y: m.b * x + m.d * y + m.f };
    },
  };
}

interface PathSegment { cmd: string; pts: number[][] }

function parsePath(d: string): PathSegment[] {
  const tokens = d.match(/[a-zA-Z]|-?\d*\.?\d+(?:e[-+]?\d+)?/gi) ?? [];
  const segments: PathSegment[] = [];
  let i = 0;
  let cmd = '';
  while (i < tokens.length) {
    const token = tokens[i];
    if (/[a-zA-Z]/.test(token)) {
      cmd = token;
      i += 1;
      continue;
    }
    const num = () => Number.parseFloat(tokens[i++]);
    switch (cmd) {
      case 'M': case 'L': segments.push({ cmd, pts: [[num(), num()]] }); break;
      case 'C': segments.push({ cmd, pts: [[num(), num()], [num(), num()], [num(), num()]] }); break;
      case 'Q': segments.push({ cmd, pts: [[num(), num()], [num(), num()]] }); break;
      case 'Z': case 'z': segments.push({ cmd: 'Z', pts: [] }); break;
      default: i += 1; break;
    }
  }
  return segments;
}

function installSvgPolyfills(): void {
  const svgProto = (globalThis as unknown as { SVGSVGElement?: { prototype: Record<string, unknown> } }).SVGSVGElement?.prototype;
  if (svgProto) {
    if (typeof svgProto.createSVGMatrix !== 'function') {
      svgProto.createSVGMatrix = function (this: unknown, source?: Partial<AffineMatrix>) {
        const mat = newMatrix();
        if (source) Object.assign(mat, source);
        return mat;
      };
    }
    if (typeof svgProto.createSVGPoint !== 'function') {
      svgProto.createSVGPoint = () => newPoint();
    }
    if (typeof svgProto.createSVGTransform !== 'function') {
      svgProto.createSVGTransform = () => ({ matrix: newMatrix(), setMatrix(m: AffineMatrix) { this.matrix = newMatrix(m.a, m.b, m.c, m.d, m.e, m.f); } });
    }
    if (typeof svgProto.createSVGTransformFromMatrix !== 'function') {
      svgProto.createSVGTransformFromMatrix = (m: AffineMatrix) => {
        const t: { matrix: AffineMatrix; setMatrix(m: AffineMatrix): void } = { matrix: newMatrix(), setMatrix(mm) { this.matrix = newMatrix(mm.a, mm.b, mm.c, mm.d, mm.e, mm.f); } };
        t.setMatrix(m);
        return t;
      };
    }
  }

  const graphicsProto = (globalThis as unknown as { SVGGraphicsElement?: { prototype: Record<string, unknown> } }).SVGGraphicsElement?.prototype;
  if (graphicsProto && typeof graphicsProto.getBBox !== 'function') {
    graphicsProto.getBBox = function (this: SVGElement) {
      const text = this.textContent ?? '';
      return { x: 0, y: 0, width: Math.max(text.length * 6, 1), height: 12 };
    };
  }
  if (graphicsProto && typeof graphicsProto.getCTM !== 'function') {
    graphicsProto.getCTM = () => newMatrix();
  }
  const svgElProto = (globalThis as unknown as { SVGElement?: { prototype: Record<string, unknown> } }).SVGElement?.prototype;
  if (svgElProto && typeof svgElProto.getScreenCTM !== 'function') {
    svgElProto.getScreenCTM = () => newMatrix();
  }

  const pathProto = (globalThis as unknown as { SVGPathElement?: { prototype: Record<string, unknown> } }).SVGPathElement?.prototype;
  if (pathProto && typeof pathProto.getTotalLength !== 'function') {
    pathProto.getTotalLength = function (this: SVGPathElement) {
      return samplePath(this.getAttribute('d') ?? '').length;
    };
    pathProto.getPointAtLength = function (this: SVGPathElement, length: number) {
      return samplePath(this.getAttribute('d') ?? '').pointAt(length);
    };
  }
  const textProto = (globalThis as unknown as { SVGTextElement?: { prototype: Record<string, unknown> } }).SVGTextElement?.prototype;
  if (textProto && typeof textProto.getComputedTextLength !== 'function') {
    textProto.getComputedTextLength = function (this: SVGElement) {
      return Math.max((this.textContent ?? '').length * 6, 1);
    };
  }
}

interface Polyline { length: number; pointAt(len: number): { x: number; y: number } }

function samplePath(d: string): Polyline {
  const pts: Array<{ x: number; y: number }> = [];
  let cursor = { x: 0, y: 0 };
  let start = cursor;
  for (const seg of parsePath(d)) {
    const end = seg.pts[seg.pts.length - 1];
    if (seg.cmd === 'C' || seg.cmd === 'Q') {
      const p0 = cursor;
      const p1 = { x: seg.pts[0][0], y: seg.pts[0][1] };
      const p2 = seg.cmd === 'C' ? { x: seg.pts[1][0], y: seg.pts[1][1] } : p1;
      const p3 = { x: end[0], y: end[1] };
      const steps = 16;
      for (let t = 1 / steps; t <= 1; t += 1 / steps) {
        const mt = 1 - t;
        const x = mt ** 3 * p0.x + 3 * mt * mt * t * p1.x + 3 * mt * t * t * p2.x + t ** 3 * p3.x;
        const y = mt ** 3 * p0.y + 3 * mt * mt * t * p1.y + 3 * mt * t * t * p2.y + t ** 3 * p3.y;
        pts.push({ x, y });
      }
    } else if (seg.cmd === 'Z') {
      pts.push({ x: start.x, y: start.y });
    } else if (end) {
      pts.push({ x: end[0], y: end[1] });
    }
    if (seg.cmd === 'M') { start = { x: end[0], y: end[1] }; }
    cursor = { x: end?.[0] ?? cursor.x, y: end?.[1] ?? cursor.y };
  }
  const lengths: number[] = [0];
  let total = 0;
  for (let i = 1; i < pts.length; i += 1) {
    total += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    lengths.push(total);
  }
  return {
    length: total,
    pointAt(len: number) {
      if (pts.length === 0) return { x: 0, y: 0 };
      const clamped = Math.min(Math.max(len, 0), total);
      let i = 1;
      while (i < lengths.length - 1 && lengths[i] < clamped) i += 1;
      const span = lengths[i] - lengths[i - 1] || 1;
      const ratio = (clamped - lengths[i - 1]) / span;
      return {
        x: pts[i - 1].x + (pts[i].x - pts[i - 1].x) * ratio,
        y: pts[i - 1].y + (pts[i].y - pts[i - 1].y) * ratio,
      };
    },
  };
}

export { installSvgPolyfills };
