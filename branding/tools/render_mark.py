"""Vectolyn mark as a 3D object, rendered with Cycles. Usage:
blender -b --factory-startup --python render_mark.py -- <job> <output> [width height samples [view [exposure]]]
Jobs: hero, social, release, readme_dark, readme_light, mark_alpha, turntable, test
Geometry: the 8x8 grid (core.MARK_A) as cubes, merged, inner faces removed, edges bevelled.

Compute device: set VECTOLYN_CYCLES_DEVICE to HIP (default, AMD), CUDA, OPTIX, METAL, ONEAPI or CPU.
A missing GPU aborts instead of silently falling back to the CPU (much slower); choose CPU explicitly if you want it."""
import os
import math
import sys

import bmesh
import bpy
from mathutils import Vector

MARK = ["XX....XX", ".XX..XX.", ".XX..XX.", "..XXXX..", "...XX...", "........", "...GG...", "...GG..."]
argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else ["test", "test.png"]
JOB, OUT = argv[0], argv[1]
RES = (int(argv[2]), int(argv[3])) if len(argv) > 3 and int(argv[3]) > 0 else None  # 0 = job size
SAMPLES = int(argv[4]) if len(argv) > 4 else 256
CELL = 0.1


def srgb(h):
    def lin(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return tuple(lin(int(h[i:i + 2], 16)) for i in (1, 3, 5)) + (1.0,)


# ---------------------------------------------------------------- Clear the scene
bpy.ops.wm.read_factory_settings(use_empty=True)
sc = bpy.context.scene
sc.render.engine = "CYCLES"
prefs = bpy.context.preferences.addons["cycles"].preferences
DEVICE = os.environ.get("VECTOLYN_CYCLES_DEVICE", "HIP").upper()
if DEVICE == "CPU":
    sc.cycles.device = "CPU"
    print("DEVICE CPU")
else:
    prefs.compute_device_type = DEVICE  # the reference renders used an AMD GPU via HIP
    prefs.get_devices()
    for d in prefs.devices:
        d.use = (d.type == DEVICE)
    gpus = [d.name for d in prefs.devices if d.use]
    if not gpus:
        raise SystemExit(f"ERROR: no {DEVICE} device found; aborting instead of falling back to the CPU")
    sc.cycles.device = "GPU"
    print(f"DEVICE GPU {DEVICE}:", ", ".join(gpus))
sc.cycles.samples = SAMPLES
sc.cycles.use_denoising = True
sc.cycles.denoiser = "OPENIMAGEDENOISE"
try:
    sc.cycles.denoising_use_gpu = True
except AttributeError:
    pass
sc.cycles.max_bounces = 12
sc.cycles.glossy_bounces = 6
sc.cycles.transmission_bounces = 8
VIEW = argv[5] if len(argv) > 5 else "khronos"
if VIEW == "agx":
    sc.view_settings.view_transform = "AgX"
    sc.view_settings.look = "AgX - Punchy"
else:  # Khronos PBR Neutral keeps the brand hues
    sc.view_settings.view_transform = "Khronos PBR Neutral"
sc.view_settings.exposure = float(argv[6]) if len(argv) > 6 else -2.2


# ---------------------------------------------------------------- Geometry

def voxel_obj(name, key):
    bm = bmesh.new()
    for r, row in enumerate(MARK):
        for c, v in enumerate(row):
            if v != key:
                continue
            x = (c - 4 + 0.5) * CELL
            z = (4 - r - 0.5) * CELL
            res = bmesh.ops.create_cube(bm, size=CELL)
            bmesh.ops.translate(bm, vec=Vector((x, 0, z)), verts=res["verts"])
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    seen = {}
    for f in bm.faces:
        k = tuple(sorted(v.index for v in f.verts))
        seen.setdefault(k, []).append(f)
    bmesh.ops.delete(bm, geom=[f for fs in seen.values() if len(fs) > 1 for f in fs], context="FACES_ONLY")
    bmesh.ops.dissolve_limit(bm, angle_limit=math.radians(1), verts=bm.verts, edges=bm.edges)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    sc.collection.objects.link(ob)
    bev = ob.modifiers.new("bevel", "BEVEL")
    bev.width = CELL * 0.07
    bev.segments = 5
    bev.limit_method = "ANGLE"
    bev.harden_normals = True
    for p in me.polygons:
        p.use_smooth = True
    return ob


def mat_cyan():
    m = bpy.data.materials.new("cyan")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = srgb("#00B2C4")
    b.inputs["Roughness"].default_value = 0.32
    b.inputs["Coat Weight"].default_value = 1.0
    b.inputs["Coat Roughness"].default_value = 0.04
    b.inputs["Subsurface Weight"].default_value = 0.0
    b.inputs["Subsurface Radius"].default_value = (0.2, 0.8, 1.0)
    b.inputs["Emission Color"].default_value = srgb("#00E5FF")
    b.inputs["Emission Strength"].default_value = 0.015
    return m


def mat_gold():
    m = bpy.data.materials.new("gold")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = srgb("#FFCF45")
    b.inputs["Metallic"].default_value = 1.0
    b.inputs["Roughness"].default_value = 0.3
    b.inputs["Coat Weight"].default_value = 0.3
    b.inputs["Coat Roughness"].default_value = 0.03
    return m


def mat_floor(col, rough=0.38):
    m = bpy.data.materials.new("floor")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = srgb(col)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Specular IOR Level"].default_value = 0.5
    return m


V = voxel_obj("V", "X")
G = voxel_obj("Gold", "G")
V.data.materials.append(mat_cyan())
G.data.materials.append(mat_gold())
root = bpy.data.objects.new("root", None)
sc.collection.objects.link(root)
V.parent = root
G.parent = root

LIGHT_MODE = JOB == "readme_light"
BG = "#E9EDF1" if LIGHT_MODE else "#14111F"
FLOOR_COL = "#DDE2E8" if LIGHT_MODE else "#060509"

floor_z = -0.48
bpy.ops.mesh.primitive_plane_add(size=40, location=(0, 0, floor_z))
floor = bpy.context.active_object
floor.data.materials.append(mat_floor(FLOOR_COL, 0.5 if LIGHT_MODE else 0.34))

world = bpy.data.worlds.new("w")
sc.world = world
world.use_nodes = True
wn, wl = world.node_tree.nodes, world.node_tree.links
bg_cam = wn["Background"]
bg_cam.inputs["Color"].default_value = srgb(BG)
bg_cam.inputs["Strength"].default_value = 1.0
studio = wn.new("ShaderNodeBackground")  # studio gradient only for reflections (gold, lacquer)
grad = wn.new("ShaderNodeTexGradient")
tc = wn.new("ShaderNodeTexCoord")
sep = wn.new("ShaderNodeSeparateXYZ")
ramp = wn.new("ShaderNodeValToRGB")
wl.new(tc.outputs["Generated"], sep.inputs[0])
wl.new(sep.outputs["Z"], ramp.inputs["Fac"])
ramp.color_ramp.elements[0].position = 0.45
ramp.color_ramp.elements[0].color = (0.004, 0.004, 0.006, 1)
ramp.color_ramp.elements[1].position = 0.75
ramp.color_ramp.elements[1].color = (0.55, 0.58, 0.62, 1)
wl.new(ramp.outputs["Color"], studio.inputs["Color"])
studio.inputs["Strength"].default_value = 0.0 if not LIGHT_MODE else 1.2
lp = wn.new("ShaderNodeLightPath")
mix = wn.new("ShaderNodeMixShader")
wl.new(lp.outputs["Is Glossy Ray"], mix.inputs["Fac"])
wl.new(bg_cam.outputs["Background"], mix.inputs[1])
wl.new(studio.outputs["Background"], mix.inputs[2])
wl.new(mix.outputs["Shader"], wn["World Output"].inputs["Surface"])


# ---------------------------------------------------------------- Lighting

def area(name, loc, size, power, color=(1, 1, 1), target=(0, 0, 0), shape="RECTANGLE", sy=None, spread=50,
         glossy=True):
    ld = bpy.data.lights.new(name, "AREA")
    ld.energy = power
    ld.spread = math.radians(spread)
    ld.color = color
    ld.shape = shape
    ld.size = size
    if sy:
        ld.size_y = sy
    ob = bpy.data.objects.new(name, ld)
    ob.location = loc
    sc.collection.objects.link(ob)
    ob.visible_glossy = glossy
    d = Vector(target) - Vector(loc)
    ob.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    return ob


area("key", (-1.5, -1.6, 1.8), 1.4, 120 if not LIGHT_MODE else 220, spread=40)
area("rim", (1.3, 1.5, 0.9), 0.8, 320, (0.8, 0.96, 1.0), spread=35)
area("fill", (1.8, -2.2, 0.3), 2.0, 25, spread=45, glossy=False)
area("top", (0, 0.3, 2.2), 1.2, 45, spread=35)
strip = area("strip", (-1.7, 0.6, 0.2), 0.12, 160, (0.7, 0.95, 1.0), sy=1.4, spread=30, glossy=False)
v_coll = bpy.data.collections.new("v_recv")
v_coll.objects.link(V)
strip.light_linking.receiver_collection = v_coll
card = area("card", (1.3, -4.6, 0.7), 3.0, 520, sy=1.2, spread=25)  # reflection card for gold only (light linking)
gold_coll = bpy.data.collections.new("gold_recv")
gold_coll.objects.link(G)
card.light_linking.receiver_collection = gold_coll


# ---------------------------------------------------------------- Camera
cam_d = bpy.data.cameras.new("cam")
cam_d.lens = 85
cam = bpy.data.objects.new("cam", cam_d)
sc.collection.objects.link(cam)
sc.camera = cam
target = Vector((0, 0, -0.02))
cam.location = Vector((1.25, -3.55, 1.05))
cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
cam_d.dof.use_dof = True
cam_d.dof.focus_distance = (target - cam.location).length - 0.05
cam_d.dof.aperture_fstop = 4.0
cam_d.sensor_width = 36

JOBS = {  # width, height, shift x (fraction of the width), lens at 24 mm sensor height
    "test": (960, 540, 0.0, 60),
    "hero": (3840, 2160, 0.0, 60),
    "social": (2560, 1280, -0.26, 64),
    "release": (2560, 640, -0.32, 86),
    "readme_dark": (2560, 560, -0.33, 88),
    "readme_light": (2560, 560, -0.33, 88),
    "mark_alpha": (2048, 2048, 0.0, 62),
    "turntable": (1280, 720, 0.0, 68),
}
w, h, shx, lens = JOBS[JOB]
if RES:
    w, h = RES
sc.render.resolution_x, sc.render.resolution_y = w, h
sc.render.resolution_percentage = 100
cam_d.sensor_fit = "VERTICAL"
cam_d.sensor_height = 24
cam_d.lens = lens
cam_d.shift_x = shx * w / h  # with vertical sensor fit, in units of the image height
sc.render.image_settings.file_format = "PNG"
sc.render.image_settings.color_mode = "RGBA" if JOB in ("mark_alpha", "readme_light") else "RGB"
sc.render.image_settings.color_depth = "16" if JOB in ("hero", "mark_alpha") else "8"

if JOB in ("mark_alpha", "readme_light"):  # transparent, the floor only catches shadows
    sc.render.film_transparent = True
    floor.hide_render = True  # no floor shadow: the shadow catcher produced long streaks
    cam_d.dof.use_dof = False


# ---------------------------------------------------------------- Compositor: bloom
def bloom():
    try:
        tree = bpy.data.node_groups.new("comp", "CompositorNodeTree")
        sc.compositing_node_group = tree
        rl = tree.nodes.new("CompositorNodeRLayers")
        gl = tree.nodes.new("CompositorNodeGlare")
        out = tree.nodes.new("NodeGroupOutput")
        tree.interface.new_socket("Image", in_out="OUTPUT", socket_type="NodeSocketColor")
        try:
            gl.inputs["Type"].default_value = "Bloom"
        except Exception:  # noqa: BLE001
            gl.glare_type = "BLOOM"
        for nm, val in (("Threshold", 1.0), ("Strength", 0.12), ("Size", 0.45)):
            if nm in gl.inputs:
                try:
                    gl.inputs[nm].default_value = val
                except Exception:  # noqa: BLE001
                    pass
        tree.links.new(rl.outputs["Image"], gl.inputs["Image"])
        tree.links.new(gl.outputs["Image"], out.inputs[0])
        print("BLOOM ok (5.x)")
    except Exception as e:  # noqa: BLE001
        print("BLOOM 5.x failed:", e)
        try:
            sc.use_nodes = True
            t = sc.node_tree
            rl = t.nodes["Render Layers"]
            comp = t.nodes["Composite"]
            gl = t.nodes.new("CompositorNodeGlare")
            gl.glare_type = "BLOOM"
            t.links.new(rl.outputs["Image"], gl.inputs["Image"])
            t.links.new(gl.outputs["Image"], comp.inputs["Image"])
            print("BLOOM ok (alt)")
        except Exception as e2:  # noqa: BLE001
            print("BLOOM off:", e2)


if JOB not in ("mark_alpha", "readme_light"):
    bloom()

if JOB == "turntable":
    n = 240  # 8 s at 30 fps
    sc.frame_start, sc.frame_end = 1, n
    sc.render.fps = 30
    root.rotation_mode = "XYZ"
    for f in (1, n + 1):
        sc.frame_set(f)
        root.rotation_euler = (0, 0, math.radians(360 * (f - 1) / n))
        root.keyframe_insert("rotation_euler", index=2, frame=f)
    for fc in root.animation_data.action.layers[0].strips[0].channelbag(root.animation_data.action.slots[0]).fcurves \
            if hasattr(root.animation_data.action, "layers") and root.animation_data.action.layers else root.animation_data.action.fcurves:
        for kp in fc.keyframe_points:
            kp.interpolation = "LINEAR"
    cam_d.dof.use_dof = False
    sc.render.filepath = OUT  # folder/prefix_####
    bpy.ops.render.render(animation=True)
else:
    sc.render.filepath = OUT
    bpy.ops.render.render(write_still=True)
print("DONE", JOB, OUT)
