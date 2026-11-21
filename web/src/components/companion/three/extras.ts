// @ts-nocheck -- complementos del estudio v4.3 sin tipar; la API la tipa `SkynyExtras`.
// Gorros, complementos de cara y de espalda de Skyny 3D (estudio v4.3): se eligen en Ajustes ›
// Companion o en el estudio y Skyny los lleva en toda la web. El gorro se mueve con un muelle al
// moverse Skyny; el jetpack echa llamas mientras trabaja.
import * as THREE from 'three'
import { createBodyGeometry } from './model'
import { STATE_CONFIG } from './states'

export type HatKind =
  | ''
  | 'builder'
  | 'headset'
  | 'halloween'
  | 'crown'
  | 'detective'
  | 'satellite'
  | 'christmas'
  | 'summer'
export type FaceKind = '' | 'sunglasses' | 'cyber' | 'pipe'
export type BackKind = '' | 'jetpack'

export const HATS: { value: HatKind; label: string }[] = [
  { value: '', label: 'Sin gorro' },
  { value: 'builder', label: 'Ingeniero' },
  { value: 'headset', label: 'Headset' },
  { value: 'halloween', label: 'Mago' },
  { value: 'crown', label: 'Corona' },
  { value: 'detective', label: 'Detective' },
  { value: 'satellite', label: 'Antena / Halo' },
  { value: 'christmas', label: 'Navidad' },
  { value: 'summer', label: 'Verano' },
]
export const FACES: { value: FaceKind; label: string }[] = [
  { value: '', label: 'Sin complemento' },
  { value: 'sunglasses', label: 'Gafas de sol' },
  { value: 'cyber', label: 'Cybervisor' },
  { value: 'pipe', label: 'Pipa' },
]
export const BACKS: { value: BackKind; label: string }[] = [
  { value: '', label: 'Sin complemento' },
  { value: 'jetpack', label: 'Jetpack' },
]

export interface SkynyExtras {
  setHat(kind: HatKind): void
  setFace(kind: FaceKind): void
  setBack(kind: BackKind): void
  /** En cada fotograma: muelle del gorro, luz del casco y llamas del jetpack. */
  update(now: number): void
  dispose(): void
}

export function createExtras(skyny): SkynyExtras {
  const hatAnchor = new THREE.Group()
  hatAnchor.position.y = 0.69
  skyny.model.root.add(hatAnchor)
  let faceKind = '',
    backKind = ''
  let hatKind = '',
    hatSpring = 0,
    hatVelocity = 0,
    hatLast = performance.now()
  let hatMotionTarget = null,
    hatLed = null,
    hatBeam = null,
    hatSpot = null
  const hatRings = []
  const hatMaterial = (color) => new THREE.MeshStandardMaterial({ color, roughness: 0.48 })
  function hatPart(geometry, material, x = 0, y = 0, z = 0, parent = hatAnchor) {
    const mesh = new THREE.Mesh(geometry, material)
    mesh.position.set(x, y, z)
    mesh.castShadow = true
    parent.add(mesh)
    return mesh
  }
  function hatBand(radius, tube, material, y = 0) {
    const ring = hatPart(new THREE.TorusGeometry(radius, tube, 12, 64), material, 0, y)
    ring.rotation.x = Math.PI / 2
    return ring
  }
  function selectHat(kind) {
    const geometries = new Set(),
      materials = new Set()
    hatAnchor.traverse((o) => {
      if (o.geometry) geometries.add(o.geometry)
      if (o.material) materials.add(o.material)
    })
    hatAnchor.clear()
    geometries.forEach((g) => g.dispose())
    materials.forEach((m) => m.dispose())
    hatKind = kind
    hatMotionTarget = hatLed = hatBeam = hatSpot = null
    hatRings.length = 0
    hatSpring = hatVelocity = hatSpringX = hatVelocityX = 0
    previousHatMotion = null
    const white = hatMaterial(0xf5f4ef)
    const black = hatMaterial(0x172033)
    if (kind === 'builder') {
      const amber = hatMaterial(0xf4ce16)
      amber.roughness = 0.88
      amber.metalness = 0
      hatPart(
        new THREE.SphereGeometry(0.35, 40, 24, 0, Math.PI * 2, 0, Math.PI / 2),
        amber,
        0,
        0.02,
      )
      hatPart(new THREE.CylinderGeometry(0.4, 0.4, 0.035, 64), amber)
      const crest = hatPart(new THREE.TorusGeometry(0.355, 0.022, 8, 48, Math.PI), amber, 0, 0.02)
      crest.rotation.y = Math.PI / 2
      const lamp = hatPart(
        new THREE.CylinderGeometry(0.074, 0.074, 0.065, 32),
        black,
        0,
        0.12,
        0.345,
      )
      lamp.rotation.x = Math.PI / 2
      hatLed = new THREE.MeshStandardMaterial({
        color: 0xfff6cd,
        emissive: 0xffe89a,
        emissiveIntensity: 0,
      })
      const lens = hatPart(
        new THREE.CylinderGeometry(0.058, 0.058, 0.012, 32),
        hatLed,
        0,
        0.12,
        0.384,
      )
      lens.rotation.x = Math.PI / 2
      const beamMaterial = new THREE.MeshBasicMaterial({
        color: 0xffefab,
        transparent: true,
        opacity: 0.045,
        depthWrite: false,
        side: THREE.DoubleSide,
      })
      hatBeam = hatPart(
        new THREE.ConeGeometry(0.3, 1.25, 48, 1, true),
        beamMaterial,
        0,
        0.12,
        1.015,
      )
      hatBeam.rotation.x = -Math.PI / 2
      hatBeam.visible = false
      hatBeam.castShadow = false
      hatSpot = new THREE.SpotLight(0xffefbd, 0, 3, 0.23, 0.7, 1.5)
      hatSpot.position.set(0, 0.12, 0.39)
      const aim = new THREE.Object3D()
      aim.position.set(0, 0.12, 2)
      hatAnchor.add(aim, hatSpot)
      hatSpot.target = aim
    } else if (kind === 'crown') {
      const gold = hatMaterial(0xe6b744)
      gold.metalness = 0.7
      gold.roughness = 0.28
      const shape = new THREE.Shape()
      shape.moveTo(0.24, 0)
      shape.lineTo(0.3, 0.05)
      shape.lineTo(0.3, 0.14)
      shape.lineTo(0.27, 0.16)
      shape.lineTo(0.25, 0.04)
      shape.closePath()
      hatPart(new THREE.LatheGeometry(shape.getPoints(), 64), gold)
      for (let i = 0; i < 8; i++) {
        const angle = (i * Math.PI) / 4
        const point = hatPart(
          new THREE.ConeGeometry(0.065, 0.18, 4),
          gold,
          0.28 * Math.cos(angle),
          0.19,
          0.28 * Math.sin(angle),
        )
        point.rotation.y = -angle
        hatPart(
          new THREE.SphereGeometry(0.024, 12, 8),
          hatMaterial(i % 2 ? 0x38bdf8 : 0xb9254a),
          0.28 * Math.cos(angle),
          0.29,
          0.28 * Math.sin(angle),
        )
      }
    } else if (kind === 'detective') {
      const tweed = hatMaterial(0x846749)
      tweed.roughness = 0.95
      hatPart(
        new THREE.SphereGeometry(0.32, 40, 24, 0, Math.PI * 2, 0, Math.PI / 2),
        tweed,
        0,
        0.025,
      )
      for (const side of [-1, 1]) {
        const brim = hatPart(new THREE.SphereGeometry(1, 24, 12), tweed, 0, 0, side * 0.22)
        brim.scale.set(0.28, 0.025, 0.26)
        const flap = hatPart(new THREE.SphereGeometry(1, 20, 12), tweed, side * 0.31, 0.1, 0)
        flap.scale.set(0.025, 0.11, 0.13)
      }
      const seamMaterial = hatMaterial(0x514436)
      for (let panel = 0; panel < 6; panel++) {
        const angle = (panel * Math.PI) / 3
        const points = []
        for (let step = 0; step <= 24; step++) {
          const t = ((step / 24) * Math.PI) / 2
          points.push(
            new THREE.Vector3(
              0.322 * Math.sin(t) * Math.cos(angle),
              0.025 + 0.322 * Math.cos(t),
              0.322 * Math.sin(t) * Math.sin(angle),
            ),
          )
        }
        hatPart(
          new THREE.TubeGeometry(new THREE.CatmullRomCurve3(points), 24, 0.0035, 6),
          seamMaterial,
        )
      }
      for (const side of [-1, 1]) {
        const bow = hatPart(
          new THREE.SphereGeometry(1, 16, 10),
          seamMaterial,
          side * 0.035,
          0.355,
          0,
        )
        bow.scale.set(0.04, 0.022, 0.025)
      }
      hatPart(new THREE.SphereGeometry(0.018, 12, 8), seamMaterial, 0, 0.355, 0)
    } else if (kind === 'satellite') {
      const metal = hatMaterial(0xb7c5d9)
      metal.metalness = 0.72
      hatPart(new THREE.CylinderGeometry(0.05, 0.065, 0.23, 16), metal, 0, 0.12)
      hatPart(new THREE.SphereGeometry(0.06, 16, 12), hatMaterial(0x41d9ee), 0, 0.26)
      const halo = hatBand(0.32, 0.018, metal, 0.3)
      halo.rotation.z = 0.22
      for (const side of [-1, 1])
        hatPart(new THREE.SphereGeometry(0.035, 12, 8), hatMaterial(0x47e3ef), side * 0.32, 0.3)
    } else if (kind === 'headset') {
      const shellGeometry = createBodyGeometry()
      const shell = new THREE.Mesh(shellGeometry, black)
      shell.updateMatrixWorld(true)
      const ray = new THREE.Raycaster()
      const mounts = []
      for (const side of [-1, 1]) {
        ray.set(new THREE.Vector3(side * 2, 0.13, 0), new THREE.Vector3(-side, 0, 0))
        const hit = ray.intersectObject(shell)[0]
        if (!hit) throw new Error('Headset fitting missed the chassis')
        const normal = hit.face.normal.clone().normalize()
        const origin = hit.point.clone()
        let support = 0
        const vertex = new THREE.Vector3()
        const positions = shellGeometry.attributes.position
        for (let i = 0; i < positions.count; i++) {
          vertex.fromBufferAttribute(positions, i).sub(origin)
          const along = vertex.dot(normal)
          if (vertex.lengthSq() - along * along <= 0.13 ** 2) support = Math.max(support, along)
        }
        const mount = new THREE.Group()
        mount.position.copy(origin).addScaledVector(normal, support + 0.05)
        mount.position.y -= hatAnchor.position.y
        mount.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), normal)
        hatAnchor.add(mount)
        hatPart(new THREE.CylinderGeometry(0.115, 0.115, 0.09, 32), black, 0, 0, 0, mount)
        const neon = new THREE.MeshStandardMaterial({
          color: 0x38bdf8,
          emissive: 0x38bdf8,
          emissiveIntensity: 0.8,
        })
        const ring = hatPart(
          new THREE.TorusGeometry(0.092, 0.013, 10, 32),
          neon,
          0,
          0.051,
          0,
          mount,
        )
        ring.rotation.x = Math.PI / 2
        hatRings.push(neon)
        mounts.push(mount.position.clone())
      }
      const bandPoints = [mounts[0]]
      const startAngle = Math.atan2(0.13, mounts[1].x)
      for (let i = 1; i < 64; i++) {
        const angle = Math.PI - startAngle - ((Math.PI - 2 * startAngle) * i) / 64
        const direction = new THREE.Vector3(Math.cos(angle), Math.sin(angle), 0)
        ray.set(direction.clone().multiplyScalar(2), direction.clone().negate())
        const hit = ray.intersectObject(shell)[0]
        if (!hit) throw new Error('Headband fitting missed the chassis')
        const point = hit.point.clone().addScaledVector(direction, 0.043)
        point.y -= hatAnchor.position.y
        bandPoints.push(point)
      }
      bandPoints.push(mounts[1])
      shellGeometry.dispose()
      const bandCurve = new THREE.CatmullRomCurve3(bandPoints)
      hatPart(new THREE.TubeGeometry(bandCurve, 192, 0.035, 12), black)
      const boom = new THREE.CatmullRomCurve3([
        mounts[0],
        new THREE.Vector3(mounts[0].x - 0.025, mounts[0].y - 0.04, 0.16),
        new THREE.Vector3(-0.57, -0.76, 0.46),
        new THREE.Vector3(-0.46, -0.88, 0.62),
        new THREE.Vector3(-0.32, -0.96, 0.66),
      ])
      hatPart(new THREE.TubeGeometry(boom, 64, 0.015, 8), black)
      hatPart(new THREE.SphereGeometry(0.04, 16, 12), black, -0.32, -0.96, 0.66)
    } else if (kind === 'halloween') {
      const purple = hatMaterial(0x4a3570),
        orange = hatMaterial(0xff8a3d)
      hatPart(new THREE.CylinderGeometry(0.47, 0.47, 0.065, 64), purple, 0, 0.022)
      hatMotionTarget = new THREE.Group()
      hatAnchor.add(hatMotionTarget)
      hatPart(
        new THREE.ConeGeometry(0.29, 0.64, 48),
        purple,
        0,
        0.3,
        -0.03,
        hatMotionTarget,
      ).rotation.x = -0.15
      hatBand(0.265, 0.035, orange, 0.08)
      hatPart(new THREE.BoxGeometry(0.105, 0.08, 0.022), hatMaterial(0xe8bc4a), 0, 0.09, 0.285)
    } else if (kind === 'christmas') {
      const red = hatMaterial(0xe5383b)
      hatBand(0.28, 0.065, white)
      const curve = new THREE.CatmullRomCurve3([
        new THREE.Vector3(0, 0.02, 0),
        new THREE.Vector3(0.03, 0.25, 0),
        new THREE.Vector3(0.2, 0.43, 0),
        new THREE.Vector3(0.36, 0.3, 0),
      ])
      const geometry = new THREE.TubeGeometry(curve, 40, 0.26, 32)
      const pos = geometry.attributes.position
      for (let i = 0; i < pos.count; i++) {
        const row = Math.floor(i / 33),
          center = curve.getPointAt(row / 40),
          v = new THREE.Vector3().fromBufferAttribute(pos, i)
        v.sub(center)
          .multiplyScalar(1 - row / 40)
          .add(center)
        pos.setXYZ(i, v.x, v.y, v.z)
      }
      geometry.computeVertexNormals()
      const fabric = hatPart(geometry, red)
      const fabricRest = new Float32Array(pos.array)
      hatMotionTarget = new THREE.Group()
      hatMotionTarget.position.set(0.36, 0.3, 0)
      hatAnchor.add(hatMotionTarget)
      hatPart(new THREE.SphereGeometry(0.095, 24, 16), white, 0, 0, 0, hatMotionTarget)
      hatMotionTarget.userData.fabric = fabric
      hatMotionTarget.userData.fabricRest = fabricRest
    } else if (kind === 'summer') {
      const straw = hatMaterial(0xd9bc7f)
      straw.roughness = 0.93
      const weave = hatMaterial(0xc4a565)
      const ribbon = hatMaterial(0xa51f24)
      ribbon.roughness = 0.82
      const profile = [
        [0.001, 0.305],
        [0.09, 0.302],
        [0.18, 0.285],
        [0.245, 0.245],
        [0.28, 0.19],
        [0.295, 0.1],
        [0.3, 0.025],
        [0.37, 0.005],
        [0.46, -0.005],
        [0.5, 0.005],
        [0.505, -0.012],
        [0.46, -0.025],
        [0.36, -0.015],
        [0.285, 0.0],
        [0.278, 0.1],
        [0.265, 0.19],
        [0.23, 0.23],
        [0.16, 0.265],
        [0.001, 0.283],
      ]
      hatPart(
        new THREE.LatheGeometry(
          profile.map(([r, y]) => new THREE.Vector2(r, y)),
          96,
        ),
        straw,
      )
      const band = hatPart(
        new THREE.CylinderGeometry(0.295, 0.303, 0.085, 64, 1, true),
        ribbon,
        0,
        0.067,
      )
      band.material.side = THREE.DoubleSide
      for (const [radius, y] of [
        [0.08, 0.306],
        [0.14, 0.297],
        [0.19, 0.281],
        [0.23, 0.26],
        [0.26, 0.23],
        [0.278, 0.2],
        [0.287, 0.17],
        [0.293, 0.14],
        [0.326, 0.021],
        [0.35, 0.014],
        [0.377, 0.007],
        [0.405, 0.003],
        [0.433, 0.0],
        [0.462, 0.0],
        [0.49, 0.008],
      ]) {
        hatBand(radius, 0.0035, weave, y)
      }
      hatPart(new THREE.BoxGeometry(0.022, 0.084, 0.008), ribbon, 0, 0.067, 0.303)
    }
    if (kind && kind !== 'headset') {
      for (const child of hatAnchor.children) child.position.y -= kind === 'builder' ? 0.1 : 0.035
    }
  }
  const faceExtra = new THREE.Group(),
    backExtra = new THREE.Group()
  skyny.model.visor.add(faceExtra)
  skyny.model.root.add(backExtra)
  const plasma = []
  function clearExtra(group) {
    const geometries = new Set(),
      materials = new Set()
    group.traverse((o) => {
      if (o.geometry) geometries.add(o.geometry)
      if (o.material) materials.add(o.material)
    })
    group.clear()
    geometries.forEach((g) => g.dispose())
    materials.forEach((m) => m.dispose())
  }
  function selectFace(kind) {
    clearExtra(faceExtra)
    if (!kind) return
    if (kind === 'pipe') {
      const pipe = new THREE.Group()
      pipe.position.set(0.14, -0.2, 0.18)
      pipe.rotation.y = -Math.PI / 2 + Math.PI / 9
      faceExtra.add(pipe)
      const wood = hatMaterial(0x8e3f20),
        stemMaterial = hatMaterial(0x101113)
      wood.roughness = 0.32
      stemMaterial.roughness = 0.22
      const stem = new THREE.CatmullRomCurve3([
        new THREE.Vector3(0.14, -0.2, 0.18),
        new THREE.Vector3(0.24, -0.23, 0.2),
        new THREE.Vector3(0.33, -0.34, 0.23),
        new THREE.Vector3(0.41, -0.49, 0.26),
      ])
      hatPart(new THREE.TubeGeometry(stem, 40, 0.03, 16), stemMaterial, 0, 0, 0, faceExtra)
      const woodShank = new THREE.CatmullRomCurve3([
        new THREE.Vector3(0.4, -0.47, 0.26),
        new THREE.Vector3(0.44, -0.56, 0.27),
        new THREE.Vector3(0.5, -0.61, 0.28),
        new THREE.Vector3(0.57, -0.58, 0.28),
      ])
      hatPart(new THREE.TubeGeometry(woodShank, 28, 0.043, 16), wood, 0, 0, 0, faceExtra)
      const bowlProfile = [
        [0.001, 0],
        [0.04, 0.006],
        [0.075, 0.025],
        [0.1, 0.065],
        [0.108, 0.12],
        [0.102, 0.205],
        [0.075, 0.205],
        [0.068, 0.13],
        [0.048, 0.075],
        [0.001, 0.065],
      ]
      const bowl = hatPart(
        new THREE.LatheGeometry(
          bowlProfile.map(([r, y]) => new THREE.Vector2(r, y)),
          64,
        ),
        wood,
        0.6,
        -0.67,
        0.28,
        faceExtra,
      )
      bowl.rotation.z = 0.08
      const cavityMaterial = hatMaterial(0x251006)
      const cavity = new THREE.Mesh(new THREE.CircleGeometry(0.048, 32), cavityMaterial)
      cavity.rotation.x = -Math.PI / 2
      cavity.position.y = 0.077
      bowl.add(cavity)
      const mouthpiece = hatPart(
        new THREE.SphereGeometry(1, 16, 12),
        stemMaterial,
        0.14,
        -0.2,
        0.18,
        faceExtra,
      )
      mouthpiece.scale.set(0.044, 0.019, 0.032)
      for (const child of [...faceExtra.children]) {
        if (child === pipe) continue
        child.position.sub(pipe.position)
        faceExtra.remove(child)
        pipe.add(child)
      }
      return
    }
    const frame = hatMaterial(0x162032)
    const lens = new THREE.MeshStandardMaterial({
      color: kind === 'cyber' ? 0x24cfe5 : 0x192b48,
      metalness: 0.3,
      roughness: 0.25,
      transparent: true,
      opacity: kind === 'cyber' ? 0.32 : 0.72,
      depthWrite: false,
    })
    for (const side of [-1, 1]) {
      const glass = hatPart(
        new THREE.BoxGeometry(0.28, 0.16, 0.025),
        lens,
        side * 0.18,
        0.05,
        0.065,
        faceExtra,
      )
      const edges = new THREE.LineSegments(
        new THREE.EdgesGeometry(glass.geometry),
        new THREE.LineBasicMaterial({ color: kind === 'cyber' ? 0x43edff : 0x101724 }),
      )
      edges.position.copy(glass.position)
      faceExtra.add(edges)
      hatPart(new THREE.BoxGeometry(0.04, 0.025, 0.14), frame, side * 0.33, 0.09, 0, faceExtra)
    }
    hatPart(new THREE.BoxGeometry(0.1, 0.025, 0.025), frame, 0, 0.075, 0.065, faceExtra)
  }
  function selectBack(kind) {
    clearExtra(backExtra)
    plasma.length = 0
    if (!kind) return
    const shell = hatMaterial(0x253247),
      silver = hatMaterial(0x9aaec4)
    hatPart(new THREE.BoxGeometry(0.42, 0.38, 0.15), shell, 0, -0.04, -0.58, backExtra)
    for (const side of [-1, 1]) {
      hatPart(
        new THREE.CylinderGeometry(0.1, 0.11, 0.43, 24),
        silver,
        side * 0.27,
        -0.04,
        -0.62,
        backExtra,
      )
      hatPart(
        new THREE.CylinderGeometry(0.115, 0.08, 0.09, 24),
        shell,
        side * 0.27,
        -0.29,
        -0.62,
        backExtra,
      )
      for (const [radius, length, color] of [
        [0.075, 0.34, 0x16a9ff],
        [0.038, 0.25, 0xe4ffff],
      ]) {
        const geo = new THREE.ConeGeometry(radius, length, 24)
        geo.translate(0, length / 2, 0)
        geo.rotateZ(Math.PI)
        const flame = hatPart(
          geo,
          new THREE.MeshBasicMaterial({
            color,
            transparent: true,
            opacity: 0.78,
            depthWrite: false,
          }),
          side * 0.27,
          -0.33,
          -0.62,
          backExtra,
        )
        flame.castShadow = false
        plasma.push(flame)
      }
    }
  }
  function updateExtras(now) {
    const on = ['running', 'coding', 'planning', 'healing', 'deploying'].includes(skyny.state)
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    plasma.forEach((flame, i) => {
      flame.visible = on
      flame.scale.y = reduced ? 1 : 0.86 + 0.14 * Math.sin(now / 65 + i)
    })
  }
  let previousHatMotion = null
  let hatSpringX = 0,
    hatVelocityX = 0
  function updateHat(now) {
    const dt = Math.min(0.04, Math.max(0.001, (now - hatLast) / 1000))
    hatLast = now
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const root = skyny.model.root
    const motion = { z: root.rotation.z, x: root.rotation.x, y: root.position.y }
    const previous = previousHatMotion || motion
    previousHatMotion = motion
    const target = reduced
      ? 0
      : Math.max(
          -0.23,
          Math.min(
            0.23,
            (-(motion.z - previous.z) / dt) * 0.65 - ((motion.y - previous.y) / dt) * 0.95,
          ),
        )
    const targetX = reduced
      ? 0
      : Math.max(-0.18, Math.min(0.18, (-(motion.x - previous.x) / dt) * 0.55))
    if (reduced) {
      hatSpring = hatVelocity = hatSpringX = hatVelocityX = 0
    }
    for (let i = 0; i < 4; i++) {
      const step = dt / 4
      hatVelocity += ((target - hatSpring) * 65 - hatVelocity * 7) * step
      hatSpring += hatVelocity * step
      hatVelocityX += ((targetX - hatSpringX) * 65 - hatVelocityX * 7) * step
      hatSpringX += hatVelocityX * step
    }
    if (hatMotionTarget) {
      if (hatKind === 'christmas') {
        if (!hatMotionTarget.userData.rest)
          hatMotionTarget.userData.rest = hatMotionTarget.position.clone()
        hatMotionTarget.position.copy(hatMotionTarget.userData.rest)
        const offset = new THREE.Vector3(hatSpring * 0.23, hatSpringX * 0.14, hatSpringX * 0.2)
        offset.clampLength(0, 0.035)
        hatMotionTarget.position.add(offset)
        const fabric = hatMotionTarget.userData.fabric
        const rest = hatMotionTarget.userData.fabricRest
        const vertices = fabric.geometry.attributes.position
        for (let i = 0; i < vertices.count; i++) {
          const t = Math.max(0, Math.min(1, (Math.floor(i / 33) / 40 - 0.45) / 0.55))
          const weight = t * t * (3 - 2 * t)
          vertices.setXYZ(
            i,
            rest[i * 3] + offset.x * weight,
            rest[i * 3 + 1] + offset.y * weight,
            rest[i * 3 + 2] + offset.z * weight,
          )
        }
        vertices.needsUpdate = true
        fabric.geometry.computeVertexNormals()
        fabric.geometry.computeBoundingSphere()
      } else if (hatKind === 'halloween') {
        hatMotionTarget.rotation.z = hatSpring * 0.38
        hatMotionTarget.rotation.x = hatSpringX * 0.38
      } else hatMotionTarget.rotation.z = hatSpring
    }
    if (hatLed) {
      const working = ['coding', 'planning', 'healing', 'running'].includes(skyny.state)
      hatLed.emissiveIntensity = working ? 1.8 : 0
      hatBeam.visible = working
      hatSpot.intensity = working ? 1.3 : 0
    }
    const activeRingCol =
      STATE_CONFIG[skyny.state]?.eyeColor || skyny.colors.body[skyny.state] || '#38bdf8'
    hatRings.forEach((material) => {
      material.color.set(activeRingCol)
      material.emissive.copy(material.color)
    })
  }
  return {
    setHat: (kind) => {
      if (kind !== hatKind) selectHat(kind)
    },
    setFace: (kind) => {
      if (kind !== faceKind) {
        faceKind = kind
        selectFace(kind)
      }
    },
    setBack: (kind) => {
      if (kind !== backKind) {
        backKind = kind
        selectBack(kind)
      }
    },
    update(now) {
      updateHat(now)
      updateExtras(now)
    },
    dispose() {
      clearExtra(hatAnchor)
      clearExtra(faceExtra)
      clearExtra(backExtra)
    },
  }
}
