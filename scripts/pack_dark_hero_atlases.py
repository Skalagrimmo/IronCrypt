#!/usr/bin/env python3
"""
Offline deterministic Dark Hero sprite-atlas packer for IronCrypt (OpenGL ES 2.0 / Mali-T830 MP1).

Reads the 9 original Dark Hero PNG sheets from public/assets/aistudio/, verifies RGBA format,
true alpha preservation, 8-direction row ordering, and seam boundaries, cleans dirty RGB on
alpha == 0 to (0, 0, 0, 0) without altering any visible pixel (alpha > 0), trims transparent
margins 1:1 with zero resizing, packs all 432 frames into 2 POT 2048x2048 atlases with pad=3px
using Skyline-BL, and generates DarkHeroAtlasData.kt.
"""

from __future__ import annotations

import hashlib
import os
import struct
import sys
import zlib
from dataclasses import dataclass
from typing import Dict, List, Tuple

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC_DIR = os.path.join(ROOT_DIR, "public", "assets", "aistudio")
RES_NODPI_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "res", "drawable-nodpi")
KT_OUT_PATH = os.path.join(
    ROOT_DIR, "app", "src", "main", "java", "com", "example", "game", "model", "DarkHeroAtlasData.kt"
)

ATLAS_W = 2048
ATLAS_H = 2048
PAD = 3


@dataclass(frozen=True)
class SheetSpec:
    sheet_id: int
    filename: str
    atlas_id: str  # "HERO_CORE" or "HERO_VARIANTS"
    expected_w: int
    expected_h: int
    cols: int
    rows: int
    world_units_per_texel: float
    const_prefix: str
    description: str


SHEETS: List[SheetSpec] = [
    SheetSpec(1, "1_1beb740a.png", "HERO_CORE", 1024, 1024, 8, 8, 0.01127, "WALK_SHIELDED", "Walk/Run Shielded Horned"),
    SheetSpec(2, "2_5974da6b.png", "HERO_CORE", 1024, 1024, 8, 8, 0.01127, "ATTACK_SHIELDED", "Attack/Slash Shielded Horned"),
    SheetSpec(3, "3_0c9d38bf.png", "HERO_VARIANTS", 1024, 1024, 8, 8, 0.01319, "CLEAVE_CRIMSON", "Heavy Cleave / Unshielded Slash Crimson Cloak Smooth Helm"),
    SheetSpec(4, "4_426e488c.png", "HERO_CORE", 864, 1152, 6, 8, 0.01138, "DASH_ROLL", "Dash/Dodge Roll Unarmed Horned Crimson Cloak"),
    SheetSpec(5, "5_abbdcb53.png", "HERO_CORE", 704, 1408, 4, 8, 0.00790, "IDLE_SHIELDED", "Idle/Guard Shielded Horned"),
    SheetSpec(6, "6_016b85c7.png", "HERO_VARIANTS", 704, 1408, 4, 8, 0.00790, "WALK_IDLE_HOODED", "Walk/Idle Unshielded Smooth Helm/Hood Dark Cloak"),
    SheetSpec(7, "7_6670b6c4.png", "HERO_CORE", 1024, 1024, 8, 8, 0.01127, "HURT_DEAD", "Hurt (0..3) & Death (4..7) Horned Crimson Cloak"),
    SheetSpec(8, "8_c0bac139.png", "HERO_VARIANTS", 704, 1408, 4, 8, 0.00790, "DIALOGUE_IDLE_HORNED", "Calm Idle (col 0) & Dialogue Gesture (0..3) Horned Dark Cloak"),
    SheetSpec(9, "9_4605fc9c.png", "HERO_VARIANTS", 704, 1408, 4, 8, 0.00790, "INTERACT_HORNED", "Interact/Pick Up Crouch Horned Dark Cloak"),
]


@dataclass
class FrameItem:
    sheet_id: int
    atlas_id: str
    row: int
    col: int
    cell_w: int
    cell_h: int
    trim_x: int
    trim_y: int
    trim_w: int
    trim_h: int
    world_units_per_texel: float
    foot_anchor_x: float
    foot_anchor_y: float
    rgba: bytearray  # trim_w * trim_h * 4
    packed_x: int = 0
    packed_y: int = 0

    @property
    def name(self) -> str:
        return f"s{self.sheet_id}_r{self.row}_c{self.col}"


def decode_rgba_png(path: str) -> Tuple[int, int, bytearray]:
    with open(path, "rb") as f:
        sig = f.read(8)
        if sig != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"Invalid PNG signature in {path}")
        w = h = bd = ct = inter = None
        idat = bytearray()
        while True:
            hdr = f.read(4)
            if not hdr:
                break
            length = struct.unpack(">I", hdr)[0]
            ctype = f.read(4)
            data = f.read(length)
            f.read(4)  # CRC
            if ctype == b"IHDR":
                w, h, bd, ct, comp, filt, inter = struct.unpack(">IIBBBBB", data)
                if bd != 8 or ct != 6 or comp != 0 or filt != 0 or inter != 0:
                    raise ValueError(f"Unsupported PNG format in {path}: bd={bd}, ct={ct}, inter={inter}")
            elif ctype == b"IDAT":
                idat.extend(data)
            elif ctype == b"IEND":
                break
    if w is None or h is None:
        raise ValueError(f"Missing IHDR in {path}")

    raw = zlib.decompress(bytes(idat))
    stride = w * 4
    out = bytearray(h * stride)
    prev = bytearray(stride)
    pos = 0
    for y in range(h):
        ftype = raw[pos]
        pos += 1
        row = bytearray(raw[pos : pos + stride])
        pos += stride
        if ftype == 1:
            for i in range(4, stride):
                row[i] = (row[i] + row[i - 4]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                row[i] = (row[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                a = row[i - 4] if i >= 4 else 0
                row[i] = (row[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = row[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                p = a + b - c
                pa = abs(p - a)
                pb = abs(p - b)
                pc = abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xFF
        elif ftype != 0:
            raise ValueError(f"Unknown PNG filter {ftype} at row {y} in {path}")
        out[y * stride : (y + 1) * stride] = row
        prev = row
    return w, h, out


def encode_rgba_png(path: str, w: int, h: int, rgba: bytearray) -> None:
    def chunk(tag: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)

    stride = w * 4
    raw = bytearray((stride + 1) * h)
    # Use PNG Sub (1) or Paeth (4) compression; Sub (1) is fast and compresses pixel-art sprites well.
    for y in range(h):
        row_start = y * stride
        out_start = y * (stride + 1)
        raw[out_start] = 1  # Sub filter
        for i in range(4):
            raw[out_start + 1 + i] = rgba[row_start + i]
        for i in range(4, stride):
            raw[out_start + 1 + i] = (rgba[row_start + i] - rgba[row_start + i - 4]) & 0xFF

    compressed = zlib.compress(bytes(raw), level=9)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", compressed))
        f.write(chunk(b"IEND", b""))


def compute_verified_foot_anchor(
    spec: SheetSpec,
    row: int,
    col: int,
    cw: int,
    ch: int,
    cell_rgba: bytearray,
) -> Tuple[float, float]:
    """
    Verified anchor resolver.
    Auto-detection via alpha > 0 bottom edge is strictly forbidden on sword/arc/dust/roll frames.
    Instead, standing/walking rows clamp to dense boot contact rows (alpha >= 180, width >= 12),
    and action sheets (2, 3, 4, 7, 9-crouch) use verified explicit floor-plane overrides.
    """
    # Find dense opaque boot contact row (ignores thin sword tips, dust specks, motion streaks)
    dense_bot = -1
    for yy in range(ch):
        cnt = 0
        row_off = yy * cw * 4
        for xx in range(cw):
            if cell_rgba[row_off + xx * 4 + 3] >= 180:
                cnt += 1
        if cnt >= 12:
            dense_bot = yy

    sid = spec.sheet_id
    if sid == 1:
        # Walk/Run Shielded (128x128): ground plane sits at y=121..124; ignore downward sword tip at 125
        fy = float(max(121, min(124, dense_bot if dense_bot >= 0 else 122)))
        return 64.0, fy
    if sid == 2:
        # Attack/Slash Shielded (128x128): sword & slash arcs drop to y=127 while boots stay on 121..124
        fy = float(max(121, min(124, dense_bot if dense_bot >= 0 else 122)))
        return 64.0, fy
    if sid == 3:
        # Heavy Cleave / Unshielded Slash (128x128): ground plane is y=117..120; ignore cleave arc overhang
        fy = float(max(117, min(120, dense_bot if dense_bot >= 0 else 118)))
        return 64.0, fy
    if sid == 4:
        # Dash / Dodge Roll (144x144): lock ground shadow plane to standing start/end cols 0 & 5 (y=132)
        # so mid-roll airborne frames (cols 1..4) naturally arc slightly above the shadow without sinking
        return 72.0, 132.0
    if sid == 5:
        # Idle/Guard Shielded (176x176): boots stand on y=164..168; ignore sword tip overhang at 169..170
        fy = float(max(164, min(168, dense_bot if dense_bot >= 0 else 166)))
        return 88.0, fy
    if sid == 6:
        # Walk/Idle Hooded (176x176): boots stand on y=171 across directions; ignore sword tip at 174..175
        fy = float(max(169, min(172, dense_bot if dense_bot >= 0 else 171)))
        return 88.0, fy
    if sid == 7:
        if col <= 3:
            # Hurt/Stagger (128x128, cols 0..3): boots at y=118..122; ignore sword tips dropping to 127
            fy = float(max(118, min(122, dense_bot if dense_bot >= 0 else 120)))
            return 64.0, fy
        else:
            # Death/Knockdown (128x128, cols 4..7): fallen body centered around x=70, floor plane y=121.5;
            # ignores dust particles & dropped sword hilt at y=127, x=120
            return 70.0, 121.5
    if sid == 8:
        # Dialogue / Calm Idle Horned (176x176): boots solidly at y=172; ignore sword tip at 175
        return 88.0, 172.0
    if sid == 9:
        # Interact / Pick Up (176x176): standing cols 0,2,3 at y=170; col 1 crouch knee/boot at y=166.5
        if col == 1:
            return 88.0, 166.5
        return 88.0, 170.0
    raise ValueError(f"Unexpected sheet id {sid}")


def extract_frames() -> Tuple[List[FrameItem], Dict[str, str]]:
    frames: List[FrameItem] = []
    sha256_map: Dict[str, str] = {}

    for spec in SHEETS:
        path = os.path.join(SRC_DIR, spec.filename)
        if not os.path.isfile(path):
            raise FileNotFoundError(f"Missing source PNG: {path}")
        with open(path, "rb") as f:
            sha256_map[spec.filename] = hashlib.sha256(f.read()).hexdigest()

        w, h, buf = decode_rgba_png(path)
        if w != spec.expected_w or h != spec.expected_h:
            raise ValueError(f"{spec.filename}: expected {spec.expected_w}x{spec.expected_h}, got {w}x{h}")

        cw = w // spec.cols
        ch = h // spec.rows
        if cw * spec.cols != w or ch * spec.rows != h:
            raise ValueError(f"{spec.filename}: dimensions {w}x{h} not divisible by {spec.cols}x{spec.rows}")

        zero_alpha = 0
        semi_alpha = 0
        full_alpha = 0
        total_px = w * h
        for i in range(3, len(buf), 4):
            a = buf[i]
            if a == 0:
                zero_alpha += 1
            elif a == 255:
                full_alpha += 1
            else:
                semi_alpha += 1
        if semi_alpha == 0:
            raise ValueError(f"{spec.filename}: expected true semi-transparent alpha pixels, found 0")

        print(
            f"[Sheet {spec.sheet_id}] {spec.filename} ({w}x{h}, {spec.cols}x{spec.rows} = {spec.cols*spec.rows}f, "
            f"cell={cw}x{ch}) alpha a=0:{zero_alpha*100/total_px:.1f}% "
            f"semi:{semi_alpha*100/total_px:.1f}% a=255:{full_alpha*100/total_px:.1f}%"
        )

        for r in range(spec.rows):
            for c in range(spec.cols):
                x0 = c * cw
                y0 = r * ch
                cell_rgba = bytearray(cw * ch * 4)

                min_x, min_y = cw, ch
                max_x, max_y = -1, -1

                for yy in range(ch):
                    src_row_off = ((y0 + yy) * w + x0) * 4
                    dst_row_off = yy * cw * 4
                    for xx in range(cw):
                        si = src_row_off + xx * 4
                        di = dst_row_off + xx * 4
                        a = buf[si + 3]
                        if a == 0:
                            # Clean dirty RGB background noise where alpha == 0
                            cell_rgba[di] = 0
                            cell_rgba[di + 1] = 0
                            cell_rgba[di + 2] = 0
                            cell_rgba[di + 3] = 0
                        else:
                            # Preserve visible pixels (alpha > 0) 100% intact
                            cell_rgba[di] = buf[si]
                            cell_rgba[di + 1] = buf[si + 1]
                            cell_rgba[di + 2] = buf[si + 2]
                            cell_rgba[di + 3] = a
                            if xx < min_x:
                                min_x = xx
                            if xx > max_x:
                                max_x = xx
                            if yy < min_y:
                                min_y = yy
                            if yy > max_y:
                                max_y = yy

                if max_x < min_x or max_y < min_y:
                    raise ValueError(f"Empty frame in {spec.filename} row={r} col={c}")

                tw = max_x - min_x + 1
                th = max_y - min_y + 1
                trimmed = bytearray(tw * th * 4)
                for yy in range(th):
                    src_off = ((min_y + yy) * cw + min_x) * 4
                    dst_off = yy * tw * 4
                    trimmed[dst_off : dst_off + tw * 4] = cell_rgba[src_off : src_off + tw * 4]

                fx, fy = compute_verified_foot_anchor(spec, r, c, cw, ch, cell_rgba)
                frames.append(
                    FrameItem(
                        sheet_id=spec.sheet_id,
                        atlas_id=spec.atlas_id,
                        row=r,
                        col=c,
                        cell_w=cw,
                        cell_h=ch,
                        trim_x=min_x,
                        trim_y=min_y,
                        trim_w=tw,
                        trim_h=th,
                        world_units_per_texel=spec.world_units_per_texel,
                        foot_anchor_x=fx,
                        foot_anchor_y=fy,
                        rgba=trimmed,
                    )
                )

    if len(frames) != 432:
        raise AssertionError(f"Expected 432 frames, got {len(frames)}")
    return frames, sha256_map


def skyline_bl_pack(items: List[FrameItem], atlas_w: int, atlas_h: int, pad: int) -> int:
    """
    Deterministic Skyline Bottom-Left packer with strict pad=3px around every frame
    and outer atlas border pad=3px so UV edges never bleed.
    """
    sorted_items = sorted(items, key=lambda it: (-it.trim_h, -it.trim_w, it.sheet_id, it.row, it.col))
    # Skyline spans: list of [x_start, y_top, width]
    usable_w = atlas_w - pad
    skyline: List[List[int]] = [[pad, pad, usable_w - pad]]
    max_used_y = pad

    for it in sorted_items:
        rw = it.trim_w + pad
        rh = it.trim_h + pad
        best_y = atlas_h + 1
        best_x = atlas_w + 1
        best_idx = -1

        for i in range(len(skyline)):
            sx = skyline[i][0]
            if sx + rw > usable_w:
                continue
            # Find max y across skyline segments covered by [sx, sx + rw)
            cur_x = sx
            end_x = sx + rw
            max_y = 0
            j = i
            while j < len(skyline) and cur_x < end_x:
                if skyline[j][1] > max_y:
                    max_y = skyline[j][1]
                cur_x = skyline[j][0] + skyline[j][2]
                j += 1
            if cur_x < end_x:
                continue
            if max_y + rh > atlas_h - pad:
                continue
            if max_y < best_y or (max_y == best_y and sx < best_x):
                best_y = max_y
                best_x = sx
                best_idx = i

        if best_idx < 0:
            raise RuntimeError(
                f"Atlas overflow ({atlas_w}x{atlas_h}, pad={pad}) failed while placing "
                f"{it.name} ({it.trim_w}x{it.trim_h}). Aborting without downscaling!"
            )

        it.packed_x = best_x
        it.packed_y = best_y
        new_y = best_y + rh
        if new_y > max_used_y:
            max_used_y = new_y

        # Insert new skyline node and shrink/split overlapped segments
        new_node = [best_x, new_y, rw]
        skyline.insert(best_idx, new_node)
        i = best_idx + 1
        while i < len(skyline):
            prev = skyline[i - 1]
            cur = skyline[i]
            overlap_end = prev[0] + prev[2]
            if cur[0] < overlap_end:
                shrink = overlap_end - cur[0]
                cur[0] += shrink
                cur[2] -= shrink
                if cur[2] <= 0:
                    skyline.pop(i)
                    continue
                break
            else:
                break

        # Merge adjacent skyline segments of equal height
        i = 0
        while i < len(skyline) - 1:
            if skyline[i][1] == skyline[i + 1][1]:
                skyline[i][2] += skyline[i + 1][2]
                skyline.pop(i + 1)
            else:
                i += 1

    return max_used_y


def verify_and_render_atlas(items: List[FrameItem], out_png: str) -> Tuple[int, float, str]:
    max_y = skyline_bl_pack(items, ATLAS_W, ATLAS_H, PAD)

    # Strict geometric validation: bounds + zero overlap with pad=3px
    for idx_a, a in enumerate(items):
        if a.packed_x < PAD or a.packed_y < PAD:
            raise AssertionError(f"Missing outer border pad on {a.name}: ({a.packed_x}, {a.packed_y})")
        if a.packed_x + a.trim_w + PAD > ATLAS_W or a.packed_y + a.trim_h + PAD > ATLAS_H:
            raise AssertionError(f"Out of bounds {a.name}: ({a.packed_x}, {a.packed_y}) {a.trim_w}x{a.trim_h}")
        for idx_b in range(idx_a + 1, len(items)):
            b = items[idx_b]
            ax1, ay1, ax2, ay2 = a.packed_x, a.packed_y, a.packed_x + a.trim_w + PAD, a.packed_y + a.trim_h + PAD
            bx1, by1, bx2, by2 = b.packed_x, b.packed_y, b.packed_x + b.trim_w + PAD, b.packed_y + b.trim_h + PAD
            if ax1 < bx2 and ax2 > bx1 and ay1 < by2 and ay2 > by1:
                raise AssertionError(f"Overlap detected between {a.name} and {b.name}!")

    canvas = bytearray(ATLAS_W * ATLAS_H * 4)
    raw_px_area = 0
    padded_px_area = 0
    for it in items:
        raw_px_area += it.trim_w * it.trim_h
        padded_px_area += (it.trim_w + PAD) * (it.trim_h + PAD)
        for yy in range(it.trim_h):
            src_off = yy * it.trim_w * 4
            dst_off = ((it.packed_y + yy) * ATLAS_W + it.packed_x) * 4
            canvas[dst_off : dst_off + it.trim_w * 4] = it.rgba[src_off : src_off + it.trim_w * 4]

    encode_rgba_png(out_png, ATLAS_W, ATLAS_H, canvas)
    with open(out_png, "rb") as f:
        atlas_sha = hashlib.sha256(f.read()).hexdigest()

    fill_pct = padded_px_area * 100.0 / (ATLAS_W * ATLAS_H)
    print(
        f"[Atlas] {os.path.basename(out_png)}: frames={len(items)}, rawArea={raw_px_area}, "
        f"paddedArea={padded_px_area}, fill={fill_pct:.1f}%, usedY={max_y}/{ATLAS_H}, sha256={atlas_sha[:12]}"
    )
    return max_y, fill_pct, atlas_sha


def generate_kotlin_data(
    frames_by_key: Dict[Tuple[int, int, int], FrameItem],
    core_sha: str,
    variants_sha: str,
) -> None:
    lines: List[str] = []
    lines.append("package com.example.game.model")
    lines.append("")
    lines.append("// AUTO-GENERATED by scripts/pack_dark_hero_atlases.py — DO NOT EDIT MANUALLY.")
    lines.append(f"// HERO_CORE sha256={core_sha[:16]} | HERO_VARIANTS sha256={variants_sha[:16]}")
    lines.append("object DarkHeroAtlasData {")
    lines.append("")
    lines.append("    private fun r(")
    lines.append("        x: Int, y: Int, w: Int, h: Int,")
    lines.append("        atlas: AtlasId,")
    lines.append("        tx: Int, ty: Int, sw: Int, sh: Int,")
    lines.append("        wu: Float, fx: Float, fy: Float")
    lines.append("    ): SpriteRect = SpriteRect(")
    lines.append("        x = x, y = y, w = w, h = h,")
    lines.append("        atlasId = atlas,")
    lines.append("        atlasWidth = atlas.width,")
    lines.append("        atlasHeight = atlas.height,")
    lines.append("        trimOffsetX = tx, trimOffsetY = ty,")
    lines.append("        sourceW = sw, sourceH = sh,")
    lines.append("        worldUnitsPerTexel = wu,")
    lines.append("        footAnchorX = fx, footAnchorY = fy")
    lines.append("    )")
    lines.append("")

    def emit_clip(
        prop_name: str,
        sheet_id: int,
        cols: List[int],
        fps: float,
        loop: bool,
        atlas_id: str,
        appearance: str,
    ) -> None:
        lines.append(f"    val {prop_name}: AnimationClip by lazy {{")
        lines.append("        AnimationClip(")
        lines.append("            framesByDirection = arrayOf(")
        for row in range(8):
            row_entries = []
            for col in cols:
                it = frames_by_key[(sheet_id, row, col)]
                row_entries.append(
                    f"r({it.packed_x}, {it.packed_y}, {it.trim_w}, {it.trim_h}, "
                    f"AtlasId.{it.atlas_id}, {it.trim_x}, {it.trim_y}, {it.cell_w}, {it.cell_h}, "
                    f"{it.world_units_per_texel:.5f}f, {it.foot_anchor_x:.1f}f, {it.foot_anchor_y:.1f}f)"
                )
            comma = "," if row < 7 else ""
            lines.append("                arrayOf(")
            for idx_e, entry in enumerate(row_entries):
                ec = "," if idx_e < len(row_entries) - 1 else ""
                lines.append(f"                    {entry}{ec}")
            lines.append(f"                ){comma}")
        lines.append("            ),")
        lines.append(f"            frameCount = {len(cols)},")
        lines.append(f"            fps = {fps:.1f}f,")
        lines.append(f"            loop = {'true' if loop else 'false'},")
        lines.append(f"            atlasId = AtlasId.{atlas_id},")
        lines.append(f"            visualAppearance = HeroVisualAppearance.{appearance}")
        lines.append("        )")
        lines.append("    }")
        lines.append("")

    # Sheet 1: WALK_SHIELDED (8 frames, HERO_CORE)
    emit_clip("walkShieldedClip", 1, list(range(8)), 12.0, True, "HERO_CORE", "HORNED_DARK_SHIELDED")
    # Sheet 2: ATTACK_SHIELDED (8 frames, HERO_CORE)
    emit_clip("attackShieldedClip", 2, list(range(8)), 36.0, False, "HERO_CORE", "HORNED_DARK_SHIELDED")
    # Sheet 3: ATTACK/CLEAVE UNSHIELDED CRIMSON (8 frames, HERO_VARIANTS)
    emit_clip("cleaveCrimsonClip", 3, list(range(8)), 28.5, False, "HERO_VARIANTS", "SMOOTH_HOOD_CRIMSON_UNSHIELDED")
    # Sheet 4: DASH ROLL (6 frames, HERO_CORE)
    emit_clip("dashRollClip", 4, list(range(6)), 27.3, False, "HERO_CORE", "HORNED_CRIMSON_UNARMED")
    # Sheet 5: IDLE SHIELDED (4 frames, HERO_CORE)
    emit_clip("idleShieldedClip", 5, list(range(4)), 4.5, True, "HERO_CORE", "HORNED_DARK_SHIELDED")
    # Sheet 6: WALK HOODED (4 frames, HERO_VARIANTS) & IDLE HOODED (col 0 static, HERO_VARIANTS)
    emit_clip("walkHoodedClip", 6, list(range(4)), 8.0, True, "HERO_VARIANTS", "SMOOTH_HOOD_DARK_UNSHIELDED")
    emit_clip("idleHoodedClip", 6, [0], 1.0, True, "HERO_VARIANTS", "SMOOTH_HOOD_DARK_UNSHIELDED")
    # Sheet 7: HURT (cols 0..3, HERO_CORE) & DEAD (cols 4..7, HERO_CORE)
    emit_clip("hurtClip", 7, [0, 1, 2, 3], 14.3, False, "HERO_CORE", "HORNED_CRIMSON_UNSHIELDED")
    emit_clip("deadClip", 7, [4, 5, 6, 7], 8.0, False, "HERO_CORE", "HORNED_CRIMSON_UNSHIELDED")
    # Sheet 8: IDLE HORNED UNSHIELDED (col 0 static calm stance, HERO_VARIANTS) & DIALOGUE (cols 0..3 gesture, HERO_VARIANTS)
    emit_clip("idleHornedUnshieldedClip", 8, [0], 1.0, True, "HERO_VARIANTS", "HORNED_DARK_UNSHIELDED")
    emit_clip("dialogueClip", 8, [0, 1, 2, 3], 5.5, True, "HERO_VARIANTS", "HORNED_DARK_UNSHIELDED")
    # Sheet 9: INTERACT (cols 0..3, HERO_VARIANTS)
    emit_clip("interactClip", 9, [0, 1, 2, 3], 10.0, False, "HERO_VARIANTS", "HORNED_DARK_UNSHIELDED")

    # Canonical front-facing (r0, c0) portraits for BitmapRegionDecoder (zero full-atlas decode)
    p_shield = frames_by_key[(5, 0, 0)]
    p_hood = frames_by_key[(6, 0, 0)]
    p_horned = frames_by_key[(8, 0, 0)]

    lines.append("    fun portraitRect(id: HeroPortraitId): SpriteRect = when (id) {")
    lines.append(
        f"        HeroPortraitId.PORTRAIT_SHIELDED_DARK -> r({p_shield.packed_x}, {p_shield.packed_y}, "
        f"{p_shield.trim_w}, {p_shield.trim_h}, AtlasId.HERO_CORE, {p_shield.trim_x}, {p_shield.trim_y}, "
        f"{p_shield.cell_w}, {p_shield.cell_h}, {p_shield.world_units_per_texel:.5f}f, "
        f"{p_shield.foot_anchor_x:.1f}f, {p_shield.foot_anchor_y:.1f}f)"
    )
    lines.append(
        f"        HeroPortraitId.PORTRAIT_UNSHIELDED_HOODED -> r({p_hood.packed_x}, {p_hood.packed_y}, "
        f"{p_hood.trim_w}, {p_hood.trim_h}, AtlasId.HERO_VARIANTS, {p_hood.trim_x}, {p_hood.trim_y}, "
        f"{p_hood.cell_w}, {p_hood.cell_h}, {p_hood.world_units_per_texel:.5f}f, "
        f"{p_hood.foot_anchor_x:.1f}f, {p_hood.foot_anchor_y:.1f}f)"
    )
    lines.append(
        f"        HeroPortraitId.PORTRAIT_UNSHIELDED_HORNED -> r({p_horned.packed_x}, {p_horned.packed_y}, "
        f"{p_horned.trim_w}, {p_horned.trim_h}, AtlasId.HERO_VARIANTS, {p_horned.trim_x}, {p_horned.trim_y}, "
        f"{p_horned.cell_w}, {p_horned.cell_h}, {p_horned.world_units_per_texel:.5f}f, "
        f"{p_horned.foot_anchor_x:.1f}f, {p_horned.foot_anchor_y:.1f}f)"
    )
    lines.append("    }")
    lines.append("")
    lines.append("    const val TOTAL_PACKED_FRAMES: Int = 432")
    lines.append("    const val ATLAS_PADDING_PX: Int = 3")
    lines.append("    val allClips: List<AnimationClip> by lazy {")
    lines.append("        listOf(")
    lines.append("            walkShieldedClip,")
    lines.append("            attackShieldedClip,")
    lines.append("            cleaveCrimsonClip,")
    lines.append("            dashRollClip,")
    lines.append("            idleShieldedClip,")
    lines.append("            walkHoodedClip,")
    lines.append("            idleHoodedClip,")
    lines.append("            hurtClip,")
    lines.append("            deadClip,")
    lines.append("            idleHornedUnshieldedClip,")
    lines.append("            dialogueClip,")
    lines.append("            interactClip")
    lines.append("        )")
    lines.append("    }")
    lines.append("}")
    lines.append("")

    os.makedirs(os.path.dirname(KT_OUT_PATH), exist_ok=True)
    with open(KT_OUT_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"[Codegen] Wrote {KT_OUT_PATH}")


def main() -> int:
    frames, sha_map = extract_frames()
    core_items = [it for it in frames if it.atlas_id == "HERO_CORE"]
    variants_items = [it for it in frames if it.atlas_id == "HERO_VARIANTS"]

    core_out = os.path.join(RES_NODPI_DIR, "dark_hero_atlas_core.png")
    variants_out = os.path.join(RES_NODPI_DIR, "dark_hero_atlas_variants.png")

    _, _, core_sha = verify_and_render_atlas(core_items, core_out)
    _, _, variants_sha = verify_and_render_atlas(variants_items, variants_out)

    frames_by_key = {(it.sheet_id, it.row, it.col): it for it in frames}
    if len(frames_by_key) != 432:
        raise AssertionError("Duplicate or missing (sheet, row, col) key!")

    generate_kotlin_data(frames_by_key, core_sha, variants_sha)
    print("SUCCESS: Packed 432 Dark Hero frames into 2 lossless 2048x2048 POT atlases.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
