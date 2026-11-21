"""Небольшая библиотека для построения диаграмм (UML, SysML, ArchiMate) в формате SVG.

Элементы размещаются по явным координатам, соединительные линии строятся между
сторонами прямоугольников. Готовые SVG-файлы преобразуются в PNG сценарием render.js.
"""
from __future__ import annotations

from dataclasses import dataclass
from html import escape

FONT = "Liberation Sans, Arial, sans-serif"
INK = "#1a1a1a"

# Цвета слоев ArchiMate
BUSINESS = "#FFF7B0"
APPLICATION = "#C6ECFB"
TECHNOLOGY = "#CDEBC5"
NEUTRAL = "#F4F4F4"
WHITE = "#FFFFFF"


@dataclass
class Box:
    """Прямоугольная область элемента диаграммы."""

    x: float
    y: float
    w: float
    h: float

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2

    def top(self, t: float = 0.5) -> tuple[float, float]:
        return self.x + self.w * t, self.y

    def bottom(self, t: float = 0.5) -> tuple[float, float]:
        return self.x + self.w * t, self.y + self.h

    def left(self, t: float = 0.5) -> tuple[float, float]:
        return self.x, self.y + self.h * t

    def right(self, t: float = 0.5) -> tuple[float, float]:
        return self.x + self.w, self.y + self.h * t

    def side(self, name: str, t: float = 0.5) -> tuple[float, float]:
        return getattr(self, name)(t)


_MARKERS = """
<marker id="open" markerWidth="12" markerHeight="10" refX="10" refY="5" orient="auto-start-reverse">
  <path d="M1,1 L10,5 L1,9" fill="none" stroke="#1a1a1a" stroke-width="1.2"/></marker>
<marker id="filled" markerWidth="12" markerHeight="10" refX="10" refY="5" orient="auto-start-reverse">
  <path d="M1,1 L10,5 L1,9 Z" fill="#1a1a1a" stroke="#1a1a1a"/></marker>
<marker id="hollow" markerWidth="16" markerHeight="14" refX="14" refY="7" orient="auto-start-reverse">
  <path d="M1,1 L14,7 L1,13 Z" fill="#fff" stroke="#1a1a1a" stroke-width="1.2"/></marker>
<marker id="diamond" markerWidth="18" markerHeight="12" refX="17" refY="6" orient="auto-start-reverse">
  <path d="M1,6 L9,1 L17,6 L9,11 Z" fill="#1a1a1a" stroke="#1a1a1a"/></marker>
<marker id="odiamond" markerWidth="18" markerHeight="12" refX="17" refY="6" orient="auto-start-reverse">
  <path d="M1,6 L9,1 L17,6 L9,11 Z" fill="#fff" stroke="#1a1a1a" stroke-width="1.2"/></marker>
<marker id="dot" markerWidth="8" markerHeight="8" refX="4" refY="4">
  <circle cx="4" cy="4" r="3" fill="#1a1a1a"/></marker>
"""

# Вид связи -> (пунктир, маркер начала, маркер конца)
EDGE_KINDS = {
    "assoc": (False, None, None),
    "dir": (False, None, "open"),
    "dep": (True, None, "open"),
    "gen": (False, None, "hollow"),
    "real": (True, None, "hollow"),
    "comp": (False, "diamond", None),
    "aggr": (False, "odiamond", None),
    "msg": (False, None, "filled"),
    "ret": (True, None, "open"),
    "flow": (True, None, "filled"),
    "serve": (False, None, "open"),
    "assign": (False, "dot", "filled"),
}


class Diagram:
    def __init__(self, width: int, height: int):
        self.width, self.height = width, height
        self.back: list[str] = []  # рамки и области (рисуются первыми)
        self.items: list[str] = []
        self.front: list[str] = []  # подписи связей (рисуются последними)

    # ------------------------------------------------------------ примитивы
    def text(self, x, y, value, size=13, anchor="middle", weight="normal", style="normal",
             fill=INK, layer=None) -> None:
        target = self.items if layer is None else layer
        target.append(
            f'<text xml:space="preserve" x="{x:.1f}" y="{y:.1f}" font-size="{size}" text-anchor="{anchor}" '
            f'font-weight="{weight}" font-style="{style}" fill="{fill}">{escape(str(value))}</text>')

    def lines(self, cx, cy, lines, size=13, bold_first=False, anchor="middle", gap=1.25) -> None:
        """Несколько строк текста, выровненных по вертикали относительно cy.

        Строка в кавычках-елочках («…») считается стереотипом и выводится мельче.
        """
        step = size * gap
        y = cy - step * (len(lines) - 1) / 2 + size * 0.35
        name_done = False
        for line in lines:
            stereotype = line.startswith("«")
            weight = "bold" if bold_first and not stereotype and not name_done else "normal"
            if not stereotype:
                name_done = True
            self.text(cx, y, line, size - 2 if stereotype else size, anchor, weight)
            y += step

    def rect(self, x, y, w, h, fill=WHITE, stroke=INK, rx=0, dash=False, width=1.2, layer=None) -> Box:
        target = self.items if layer is None else layer
        dash_attr = ' stroke-dasharray="6,4"' if dash else ""
        target.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{width}"{dash_attr}/>')
        return Box(x, y, w, h)

    def node(self, x, y, w, h, lines, fill=WHITE, rx=0, size=13, bold=True, dash=False) -> Box:
        """Прямоугольник с текстом по центру."""
        box = self.rect(x, y, w, h, fill=fill, rx=rx, dash=dash)
        self.lines(box.cx, box.cy, lines, size, bold_first=bold)
        return box

    # ------------------------------------------------------------ UML
    def actor(self, cx, y, label_lines, size=12) -> Box:
        """Действующее лицо (человечек) с подписью."""
        s = self.items
        s.append(f'<circle cx="{cx}" cy="{y + 9}" r="8" fill="#fff" stroke="{INK}" stroke-width="1.2"/>')
        s.append(f'<path d="M{cx},{y + 17} V{y + 40} M{cx - 14},{y + 25} H{cx + 14} '
                 f'M{cx},{y + 40} L{cx - 12},{y + 58} M{cx},{y + 40} L{cx + 12},{y + 58}" '
                 f'stroke="{INK}" stroke-width="1.2" fill="none"/>')
        for index, line in enumerate(label_lines):
            self.text(cx, y + 74 + index * size * 1.2, line, size)
        return Box(cx - 16, y, 32, 60)

    def usecase(self, cx, cy, w, h, lines, size=12, fill=WHITE) -> Box:
        self.items.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{w / 2}" ry="{h / 2}" fill="{fill}" '
                          f'stroke="{INK}" stroke-width="1.2"/>')
        self.lines(cx, cy, lines, size)
        return Box(cx - w / 2, cy - h / 2, w, h)

    def cls(self, x, y, w, name, attrs=(), ops=(), stereotype=None, fill=WHITE, size=11) -> Box:
        """Класс (блок) с разделами атрибутов и операций; высота вычисляется."""
        row = size * 1.35
        head = 24 + (14 if stereotype else 0)
        height = head + (len(attrs) * row + 8 if attrs else 0) + (len(ops) * row + 8 if ops else 0)
        height = max(height, head)
        box = self.rect(x, y, w, height, fill=fill)
        ty = y + 16
        if stereotype:
            self.text(x + w / 2, ty - 2, f"«{stereotype}»", size - 1)
            ty += 14
        self.text(x + w / 2, ty, name, size + 1.5, weight="bold")
        cursor = y + head
        for section in (attrs, ops):
            if not section:
                continue
            self.items.append(f'<line x1="{x}" y1="{cursor}" x2="{x + w}" y2="{cursor}" stroke="{INK}"/>')
            ty = cursor + row * 0.8 + 2
            for line in section:
                self.text(x + 7, ty, line, size, anchor="start")
                ty += row
            cursor += len(section) * row + 8
        return box

    def component(self, x, y, w, h, lines, fill=WHITE, size=12) -> Box:
        """Компонент UML с пиктограммой в правом верхнем углу."""
        box = self.node(x, y, w, h, lines, fill=fill, size=size)
        ix, iy = x + w - 24, y + 7
        self.items.append(
            f'<rect x="{ix}" y="{iy}" width="15" height="12" fill="#fff" stroke="{INK}"/>'
            f'<rect x="{ix - 4}" y="{iy + 2}" width="8" height="3" fill="#fff" stroke="{INK}"/>'
            f'<rect x="{ix - 4}" y="{iy + 7}" width="8" height="3" fill="#fff" stroke="{INK}"/>')
        return box

    def node3d(self, x, y, w, h, title_lines, fill=NEUTRAL, size=12, depth=10) -> Box:
        """Узел развертывания (параллелепипед) с заголовком в верхней части."""
        d = depth
        self.back.append(
            f'<path d="M{x},{y} l{d},{-d} h{w} v{h} l{-d},{d} M{x + w},{y} l{d},{-d}" fill="{fill}" '
            f'stroke="{INK}" stroke-width="1.2"/>'
            f'<path d="M{x},{y} l{d},{-d} h{w} l{-d},{d} Z M{x + w},{y} l{d},{-d} v{h} l{-d},{d} Z" '
            f'fill="#DADADA" stroke="{INK}" stroke-width="1.2"/>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" stroke="{INK}" '
            f'stroke-width="1.2"/>')
        ty = y + 17
        for index, line in enumerate(title_lines):
            stereotype = line.startswith("«")
            self.text(x + w / 2, ty, line, size - 1 if stereotype else size,
                      weight="normal" if stereotype else "bold", layer=self.back)
            ty += size * 1.25
            del index
        return Box(x, y, w, h)

    def cylinder(self, x, y, w, h, lines, fill=WHITE, size=12) -> Box:
        """База данных (цилиндр)."""
        ry = 9
        self.items.append(
            f'<path d="M{x},{y + ry} v{h - 2 * ry} a{w / 2},{ry} 0 0 0 {w},0 v{-(h - 2 * ry)}" '
            f'fill="{fill}" stroke="{INK}" stroke-width="1.2"/>'
            f'<ellipse cx="{x + w / 2}" cy="{y + ry}" rx="{w / 2}" ry="{ry}" fill="{fill}" '
            f'stroke="{INK}" stroke-width="1.2"/>')
        self.lines(x + w / 2, y + h / 2 + 5, lines, size, bold_first=True)
        return Box(x, y, w, h)

    def frame(self, x, y, w, h, label, fill="none", size=12, dash=False, tab=True) -> Box:
        """Рамка (граница системы, пакет, слой) с подписью в левом верхнем углу."""
        self.rect(x, y, w, h, fill=fill, dash=dash, layer=self.back)
        if tab:
            width = len(label) * size * 0.66 + 22
            self.back.append(
                f'<path d="M{x},{y} h{width} v14 l-8,8 h{-(width - 8)} Z" fill="#fff" '
                f'stroke="{INK}" stroke-width="1.2"/>')
            self.text(x + 8, y + 15, label, size, anchor="start", weight="bold", layer=self.back)
        else:
            self.text(x + 10, y + 18, label, size, anchor="start", weight="bold", layer=self.back)
        return Box(x, y, w, h)

    def port(self, point, size=10) -> None:
        """Порт SysML: квадрат на границе блока."""
        x, y = point
        self.front.append(
            f'<rect x="{x - size / 2}" y="{y - size / 2}" width="{size}" height="{size}" fill="#fff" '
            f'stroke="{INK}" stroke-width="1.2"/>')

    def note(self, x, y, w, h, lines, size=11) -> Box:
        self.items.append(
            f'<path d="M{x},{y} h{w - 12} l12,12 v{h - 12} h{-w} Z M{x + w - 12},{y} v12 h12" '
            f'fill="#FFFDE7" stroke="{INK}"/>')
        step = size * 1.3
        ty = y + 16
        for line in lines:
            self.text(x + 8, ty, line, size, anchor="start")
            ty += step
        return Box(x, y, w, h)

    # ------------------------------------------------------------ ArchiMate
    def archi(self, x, y, w, h, lines, fill, icon="", size=12, rounded=False) -> Box:
        """Элемент ArchiMate: цвет задает слой, значок в углу – вид элемента."""
        box = self.rect(x, y, w, h, fill=fill, rx=10 if rounded else 0)
        self.lines(box.cx, box.cy + (4 if icon else 0), lines, size)
        ix, iy = x + w - 20, y + 6
        if icon == "actor":
            self.items.append(
                f'<circle cx="{ix + 6}" cy="{iy + 3}" r="3" fill="none" stroke="{INK}"/>'
                f'<path d="M{ix + 6},{iy + 6} v6 M{ix + 1},{iy + 8} h10 M{ix + 6},{iy + 12} l-4,5 '
                f'M{ix + 6},{iy + 12} l4,5" stroke="{INK}" fill="none"/>')
        elif icon == "process":
            self.items.append(
                f'<path d="M{ix - 4},{iy + 4} h10 v-3 l8,6 l-8,6 v-3 h-10 Z" fill="none" stroke="{INK}"/>')
        elif icon == "service":
            self.items.append(
                f'<rect x="{ix - 4}" y="{iy}" width="18" height="11" rx="5.5" fill="none" stroke="{INK}"/>')
        elif icon == "component":
            self.items.append(
                f'<rect x="{ix}" y="{iy}" width="13" height="12" fill="none" stroke="{INK}"/>'
                f'<rect x="{ix - 4}" y="{iy + 2}" width="7" height="3" fill="{fill}" stroke="{INK}"/>'
                f'<rect x="{ix - 4}" y="{iy + 7}" width="7" height="3" fill="{fill}" stroke="{INK}"/>')
        elif icon == "node":
            self.items.append(
                f'<path d="M{ix - 2},{iy + 3} h13 v10 h-13 Z M{ix - 2},{iy + 3} l3,-3 h13 l-3,3 '
                f'M{ix + 11},{iy + 13} l3,-3 v-10" fill="none" stroke="{INK}"/>')
        elif icon == "software":
            self.items.append(
                f'<circle cx="{ix + 4}" cy="{iy + 7}" r="6" fill="none" stroke="{INK}"/>'
                f'<circle cx="{ix + 7}" cy="{iy + 4}" r="6" fill="{fill}" stroke="{INK}"/>')
        elif icon == "data":
            self.items.append(
                f'<rect x="{ix - 2}" y="{iy}" width="15" height="12" fill="none" stroke="{INK}"/>'
                f'<line x1="{ix - 2}" y1="{iy + 4}" x2="{ix + 13}" y2="{iy + 4}" stroke="{INK}"/>')
        elif icon == "interface":
            self.items.append(
                f'<circle cx="{ix + 8}" cy="{iy + 6}" r="5" fill="none" stroke="{INK}"/>'
                f'<line x1="{ix - 4}" y1="{iy + 6}" x2="{ix + 3}" y2="{iy + 6}" stroke="{INK}"/>')
        return box

    # ------------------------------------------------------------ связи
    def edge(self, points, kind="assoc", label=None, label_at=0.5, size=11, label_dy=-5,
             label_dx=0) -> None:
        """Ломаная линия по точкам с маркерами в соответствии с видом связи."""
        dashed, start, end = EDGE_KINDS[kind]
        attrs = ' stroke-dasharray="6,4"' if dashed else ""
        if start:
            attrs += f' marker-start="url(#{start})"'
        if end:
            attrs += f' marker-end="url(#{end})"'
        path = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
        self.items.append(
            f'<polyline points="{path}" fill="none" stroke="{INK}" stroke-width="1.2"{attrs}/>')
        if label:
            # Подпись размещается на самом длинном отрезке
            segments = list(zip(points, points[1:], strict=False))
            (x1, y1), (x2, y2) = max(
                segments, key=lambda s: abs(s[1][0] - s[0][0]) + abs(s[1][1] - s[0][1]))
            lx = x1 + (x2 - x1) * label_at + label_dx
            ly = y1 + (y2 - y1) * label_at + label_dy
            for index, line in enumerate(label.split("\n")):
                width = len(line) * size * 0.56 + 6
                yy = ly + index * size * 1.2
                self.front.append(
                    f'<rect x="{lx - width / 2:.1f}" y="{yy - size + 1:.1f}" width="{width:.1f}" '
                    f'height="{size + 3}" fill="#fff" fill-opacity="0.9"/>')
                self.text(lx, yy, line, size, layer=self.front)

    def link(self, a: Box, b: Box, kind="assoc", label=None, sides=None, at=(0.5, 0.5),
             route="auto", **kwargs) -> None:
        """Соединяет два элемента прямой или ломаной под прямым углом.

        sides – стороны выхода и входа ("right", "left"), по умолчанию выбираются
        по взаимному расположению; at – положение точек на сторонах (0..1);
        route: "straight" – прямая, "hv" – сначала по горизонтали, "vh" – сначала
        по вертикали, "z" – со сгибом посередине.
        """
        if sides is None:
            dx, dy = b.cx - a.cx, b.cy - a.cy
            horizontal_gap = max(b.x - (a.x + a.w), a.x - (b.x + b.w))
            vertical_gap = max(b.y - (a.y + a.h), a.y - (b.y + b.h))
            if horizontal_gap >= vertical_gap:
                sides = ("right", "left") if dx > 0 else ("left", "right")
            else:
                sides = ("bottom", "top") if dy > 0 else ("top", "bottom")
        p1, p2 = a.side(sides[0], at[0]), b.side(sides[1], at[1])
        horizontal_start = sides[0] in ("left", "right")
        horizontal_end = sides[1] in ("left", "right")
        if route == "auto":
            if abs(p1[0] - p2[0]) < 1 or abs(p1[1] - p2[1]) < 1:
                route = "straight"
            elif horizontal_start != horizontal_end:
                route = "hv" if horizontal_start else "vh"
            else:
                route = "z"
        if route == "straight":
            points = [p1, p2]
        elif route == "hv":
            points = [p1, (p2[0], p1[1]), p2]
        elif route == "vh":
            points = [p1, (p1[0], p2[1]), p2]
        elif horizontal_start:
            mid = kwargs.pop("mid", (p1[0] + p2[0]) / 2)
            points = [p1, (mid, p1[1]), (mid, p2[1]), p2]
        else:
            mid = kwargs.pop("mid", (p1[1] + p2[1]) / 2)
            points = [p1, (p1[0], mid), (p2[0], mid), p2]
        kwargs.pop("mid", None)
        self.edge(points, kind, label, **kwargs)

    # ------------------------------------------------------------ вывод
    def svg(self) -> str:
        body = "\n".join(self.back + self.items + self.front)
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.width}" height="{self.height}" '
            f'viewBox="0 0 {self.width} {self.height}" font-family="{FONT}">\n'
            f'<defs>{_MARKERS}</defs>\n'
            f'<rect width="{self.width}" height="{self.height}" fill="#fff"/>\n{body}\n</svg>\n')

    def save(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as target:
            target.write(self.svg())
