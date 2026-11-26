// @ts-nocheck -- motor portado del estudio v4.3 sin tipar; la API que usa la web la tipa
// `SkynyScene` (scene.ts) y la prueban los tests de Skyny3D.
// Portado del estudio v4.4 (plans/skyny-3d/skyny-v4.4.html).
import * as THREE from 'three'

// =========================================================================
// 2. GEOMETRÍA PROCEDURAL DE SKYNY (Cuerpo, Bandas, Visor y Manos)
// =========================================================================

export const BODY_RADIUS = 0.78
export const HAND_SPHERE_RADIUS = 0.33

export function deformBodyPoint([x, y, z]) {
  const ny = y / BODY_RADIUS
  if (Math.abs(ny) > 0.999999) {
    x = 0
    z = 0
  }
  x *= (0.44 + (1 - ny) * 0.48) * 1.15
  z *= 0.42 + (1 - ny) * 0.2
  if (y < -0.22) y -= (y + 0.22) * 0.28
  const backCurve = Math.pow(Math.max(0, -z) / 0.64, 2)
  z -= Math.cos(x * 2.2) * 0.04 * (1 - Math.abs(ny)) * backCurve
  return [x, y, z]
}

export const vecLen = ([x, y, z]) => Math.hypot(x, y, z)
export const vecNorm = (v) => {
  const l = vecLen(v) || 1
  return [v[0] / l, v[1] / l, v[2] / l]
}

export function projectOnBody(dir, offset = 0) {
  const n = vecNorm(dir)
  let r = n
  for (let i = 0; i < 40; i++) {
    const p = vecNorm(deformBodyPoint([r[0] * BODY_RADIUS, r[1] * BODY_RADIUS, r[2] * BODY_RADIUS]))
    const diff = [n[0] - p[0], n[1] - p[1], n[2] - p[2]]
    if (vecLen(diff) < 1e-7) break
    r = vecNorm([r[0] + diff[0], r[1] + diff[1], r[2] + diff[2]])
  }
  const surfaceLen = vecLen(
    deformBodyPoint([r[0] * BODY_RADIUS, r[1] * BODY_RADIUS, r[2] * BODY_RADIUS]),
  )
  return [n[0] * (surfaceLen + offset), n[1] * (surfaceLen + offset), n[2] * (surfaceLen + offset)]
}

export function findBackZ(x, y) {
  const isInside = (z) => {
    const p = [x, y, z]
    return vecLen(p) <= vecLen(projectOnBody(p))
  }
  let lo = -1.2,
    hi = 0
  for (let i = 0; i < 40; i++) {
    const mid = (lo + hi) / 2
    if (isInside(mid)) hi = mid
    else lo = mid
  }
  return (lo + hi) / 2
}

export function createBodyGeometry() {
  const geo = new THREE.SphereGeometry(BODY_RADIUS, 72, 72)
  const pos = geo.attributes.position
  for (let i = 0; i < pos.count; i++) {
    const [nx, ny, nz] = deformBodyPoint([pos.getX(i), pos.getY(i), pos.getZ(i)])
    pos.setXYZ(i, nx, ny, nz)
  }
  geo.computeVertexNormals()
  const norm = geo.attributes.normal
  const map = new Map()
  for (let i = 0; i < pos.count; i++) {
    const key = [pos.getX(i), pos.getY(i), pos.getZ(i)].map((v) => Math.round(v * 1e6)).join()
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(i)
  }
  const avg = new THREE.Vector3()
  for (const indices of map.values()) {
    avg.set(0, 0, 0)
    for (const idx of indices) avg.add(new THREE.Vector3().fromBufferAttribute(norm, idx))
    avg.normalize()
    for (const idx of indices) norm.setXYZ(idx, avg.x, avg.y, avg.z)
  }
  return geo
}

export function createBandGeometry(points, width, offset) {
  const curve = new THREE.CatmullRomCurve3(
    points.map((p) => new THREE.Vector3(...p)),
    false,
    'centripetal',
  )
  const positions = [],
    indices = []
  const pt = new THREE.Vector3(),
    surf = new THREE.Vector3(),
    side = new THREE.Vector3()
  for (let i = 0; i <= 96; i++) {
    const u = i / 96
    curve.getPoint(u, pt)
    side.crossVectors(curve.getTangent(u), pt.clone().normalize()).normalize()
    surf.set(...projectOnBody([pt.x, pt.y, pt.z], offset))
    for (let j = 0; j <= 24; j++) {
      const p = surf.clone().addScaledVector(side, (j / 24 - 0.5) * width)
      positions.push(...(j === 12 ? surf.toArray() : projectOnBody(p.toArray(), offset)))
      if (i < 96 && j < 24) {
        const a = i * 25 + j,
          b = a + 25
        indices.push(a, a + 1, b, a + 1, b + 1, b)
      }
    }
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))
  geo.setIndex(indices)
  geo.computeVertexNormals()
  return geo
}

export function createBackBadgeGeometry() {
  const roundedTri = (shape, pts, r) => {
    const lerpPt = (a, b) => a.clone().lerp(b, r / a.distanceTo(b))
    const start = lerpPt(pts[0], pts[2])
    shape.moveTo(start.x, start.y)
    pts.forEach((p, idx) => {
      const p1 = lerpPt(p, pts[(idx + 2) % 3])
      const p2 = lerpPt(p, pts[(idx + 1) % 3])
      if (idx > 0) shape.lineTo(p1.x, p1.y)
      shape.quadraticCurveTo(p.x, p.y, p2.x, p2.y)
    })
    shape.closePath()
    return shape
  }
  const outer = roundedTri(
    new THREE.Shape(),
    [
      new THREE.Vector2(0, 0.24),
      new THREE.Vector2(-0.225, -0.163),
      new THREE.Vector2(0.225, -0.163),
    ],
    0.058,
  )
  outer.holes.push(
    roundedTri(
      new THREE.Path(),
      [
        new THREE.Vector2(0, 0.142),
        new THREE.Vector2(0.136, -0.103),
        new THREE.Vector2(-0.136, -0.103),
      ],
      0.028,
    ),
  )
  const ext = new THREE.ExtrudeGeometry(outer, {
    depth: 0.038,
    bevelEnabled: true,
    bevelSize: 0.009,
    bevelThickness: 0.009,
    bevelSegments: 4,
    curveSegments: 12,
  })
  let verts = Array.from(ext.attributes.position.array)
  ext.dispose()

  for (let iter = 0; iter < 2; iter++) {
    const next = []
    const mid = (a, b) => a.map((v, k) => (v + b[k]) / 2)
    for (let i = 0; i < verts.length; i += 9) {
      const v0 = verts.slice(i, i + 3),
        v1 = verts.slice(i + 3, i + 6),
        v2 = verts.slice(i + 6, i + 9)
      const m01 = mid(v0, v1),
        m12 = mid(v1, v2),
        m20 = mid(v2, v0)
      next.push(
        ...v0,
        ...m01,
        ...m20,
        ...m01,
        ...v1,
        ...m12,
        ...m20,
        ...m12,
        ...v2,
        ...m01,
        ...m12,
        ...m20,
      )
    }
    verts = next
  }
  const zMap = new Map(),
    vMap = new Map(),
    outPos = [],
    outIdx = []
  for (let i = 0; i < verts.length; i += 3) {
    const [x, y, z] = verts.slice(i, i + 3)
    const xyKey = `${Math.round(x * 1e6)},${Math.round(y * 1e6)}`
    if (!zMap.has(xyKey)) zMap.set(xyKey, findBackZ(x, y))
    const xyzKey = `${xyKey},${Math.round(z * 1e6)}`
    if (!vMap.has(xyzKey)) {
      vMap.set(xyzKey, outPos.length / 3)
      outPos.push(x, y, zMap.get(xyKey) - 0.032 + z)
    }
    outIdx.push(vMap.get(xyzKey))
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.Float32BufferAttribute(outPos, 3))
  geo.setIndex(outIdx)
  geo.computeVertexNormals()
  return geo
}

export const HAND_REST_POS = [
  [-0.88, -0.42, 0.12],
  [0.88, -0.42, 0.12],
]
export const BASE_Y = 0.05

export function buildSkynyModel(faceTexture) {
  const materials = {
    body: new THREE.MeshPhysicalMaterial({
      roughness: 0.38,
      metalness: 0,
      clearcoat: 0.22,
      clearcoatRoughness: 0.3,
    }),
    band: new THREE.MeshPhysicalMaterial({
      roughness: 0.78,
      metalness: 0,
      clearcoat: 0,
      clearcoatRoughness: 0.5,
      side: THREE.DoubleSide,
    }),
    hardware: new THREE.MeshPhysicalMaterial({
      roughness: 0.42,
      metalness: 0.08,
      clearcoat: 0.18,
      clearcoatRoughness: 0.32,
      side: THREE.DoubleSide,
    }),
    node: new THREE.MeshPhysicalMaterial({
      roughness: 0.38,
      metalness: 0,
      clearcoat: 0.2,
      clearcoatRoughness: 0.3,
    }),
    bezel: new THREE.MeshPhysicalMaterial({
      color: 0xc7cbd1,
      roughness: 0.32,
      metalness: 1,
      clearcoat: 0,
      side: THREE.DoubleSide,
    }),
  }

  const root = new THREE.Group()
  root.position.y = BASE_Y
  root.add(new THREE.Mesh(createBodyGeometry(), materials.body))

  const backAnchors = [
    [0, -0.145, -0.54],
    [0.108, 0.044, -0.53],
    [-0.108, 0.044, -0.53],
  ]
  const bandsPaths = [
    [
      [0, -0.42, 0.51],
      [0, -0.58, 0.38],
      [0, -0.66, 0.12],
      [0, -0.64, -0.16],
      [0, -0.46, -0.32],
      [0, -0.22, -0.36],
      backAnchors[0],
    ],
    ...[1, -1].map((s, idx) => [
      [s * 0.28, 0.22, 0.49],
      [s * 0.42, 0.29, 0.36],
      [s * 0.53, 0.32, 0.14],
      [s * 0.49, 0.282, -0.14],
      [s * 0.32, 0.176, -0.34],
      [s * 0.23, 0.12, -0.45],
      backAnchors[idx + 1],
    ]),
  ]
  for (const path of bandsPaths) {
    root.add(new THREE.Mesh(createBandGeometry(path, 0.2, 0.012), materials.band))
  }
  root.add(new THREE.Mesh(createBackBadgeGeometry(), materials.hardware))

  const visorGroup = new THREE.Group()
  visorGroup.position.set(0, -0.04, 0.5)
  visorGroup.rotation.x = -0.14

  const visorHousing = new THREE.Mesh(
    new THREE.CylinderGeometry(0.438, 0.32, 0.3, 96).rotateX(Math.PI / 2),
    materials.hardware,
  )
  visorHousing.position.z = -0.17

  const bezelShape = new THREE.Shape().absarc(0, 0, 0.44, 0, Math.PI * 2, false)
  bezelShape.holes.push(new THREE.Path().absarc(0, 0, 0.375, 0, Math.PI * 2, true))
  const bezelMesh = new THREE.Mesh(
    new THREE.ExtrudeGeometry(bezelShape, {
      depth: 0.042,
      bevelEnabled: false,
      curveSegments: 64,
    }).translate(0, 0, -0.024),
    materials.bezel,
  )
  bezelMesh.position.z = 0.02

  const screenBack = new THREE.Mesh(
    new THREE.CircleGeometry(0.39, 64),
    new THREE.MeshBasicMaterial({ color: 0x050913 }),
  )
  screenBack.position.z = -0.01

  const screenMesh = new THREE.Mesh(
    new THREE.CircleGeometry(0.38, 64),
    new THREE.MeshBasicMaterial({ map: faceTexture, toneMapped: false }),
  )
  screenMesh.position.z = 0.01

  visorGroup.add(visorHousing, bezelMesh, screenBack, screenMesh)
  root.add(visorGroup)

  const sphereGeo = new THREE.SphereGeometry(HAND_SPHERE_RADIUS, 40, 40)
  const head = new THREE.Mesh(sphereGeo, materials.node)
  head.position.set(0, 0.9, 0)
  head.visible = false

  const hands = HAND_REST_POS.map((pos) => {
    const h = new THREE.Mesh(sphereGeo, materials.node.clone())
    h.position.set(...pos)
    return h
  })
  root.add(head, ...hands)

  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(2.4, 1.3),
    new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false }),
  )
  floor.rotation.x = -Math.PI / 2
  floor.position.y = -0.92

  return { root, visor: visorGroup, head, hands, screen: screenMesh, floor, materials }
}
