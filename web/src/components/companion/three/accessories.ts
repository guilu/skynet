// @ts-nocheck -- motor portado del estudio v4.3 sin tipar; la API que usa la web la tipa
// `SkynyScene` (scene.ts) y la prueban los tests de Skyny3D.
// Portado del estudio v4.4 (plans/skyny-3d/skyny-v4.4.html).
import * as THREE from 'three'
import { clamp01, smoothstep } from './math'
import { createBodyGeometry } from './model'

// =========================================================================
// 3. ACCESORIOS 3D POR ESTADO
// =========================================================================

export const GRIP_REST = new THREE.Vector3(1.06, -0.42, 0.4)
export const BADGE_POS = new THREE.Vector3(0.89, 0.48, 0.34)
export const BADGE_SCALE = { alert: 0.62, pause: 0.95, offline: 1.05, 'cancel-x': 0.85 }

export const extrudeShape = (shape, depth, mat) =>
  new THREE.Mesh(
    new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: false, curveSegments: 48 }).translate(
      0,
      0,
      -depth / 2,
    ),
    mat,
  )

export const plasticMat = (hex) =>
  new THREE.MeshPhysicalMaterial({
    color: new THREE.Color(hex),
    roughness: 0.38,
    metalness: 0,
    clearcoat: 0.22,
    clearcoatRoughness: 0.3,
  })

export function delegationDispatch(ms) {
  const cycle = 5200,
    phase = ms % cycle
  const satellite = Math.floor(ms / cycle) % 2
  const cue = smoothstep((phase - 450) / 250) * (1 - smoothstep((phase - 750) / 350))
  const flight = smoothstep((phase - 700) / 600) * (1 - smoothstep((phase - 1850) / 900))
  return { satellite, cue, flight }
}

export function cooldownCharge(elapsedMs, moving) {
  const cycleMs = 8800
  const t = moving ? elapsedMs % cycleMs : 5200
  const progress = clamp01(t / 7400)
  const justFull = moving && t >= 7400 && t < 8400
  return {
    progress,
    justFull,
    tCycle: t,
    low: progress < 0.22,
    brightness: progress < 0.22 ? 0.52 + 0.48 * (0.5 + 0.5 * Math.cos(elapsedMs / 170)) : 1,
  }
}

export function createAccessory(type, palette) {
  const group = new THREE.Group()
  switch (type) {
    case 'relay-baton': {
      const relayGroup = new THREE.Group()
      relayGroup.name = 'relayBaton'

      // Cápsula / testigo luminoso de relevo entre agentes
      const batonUnit = new THREE.Group()
      batonUnit.name = 'batonCapsule'

      const coreMat = plasticMat(0xc4b5fd)
      coreMat.emissive = new THREE.Color(0x8b5cf6)
      coreMat.emissiveIntensity = 0.75
      const coreCylinder = new THREE.Mesh(
        new THREE.CylinderGeometry(0.048, 0.048, 0.22, 24),
        coreMat,
      )

      const capMat = plasticMat(0x38bdf8)
      capMat.emissive = new THREE.Color(0x0284c7).multiplyScalar(0.5)
      ;[-0.125, 0.125].forEach((yOff) => {
        const endCap = new THREE.Mesh(new THREE.SphereGeometry(0.052, 20, 16), capMat)
        endCap.position.y = yOff
        batonUnit.add(endCap)
      })

      // Aro de energía central en el testigo
      const bandShape = new THREE.Shape().absarc(0, 0, 0.068, 0, Math.PI * 2, false)
      bandShape.holes.push(new THREE.Path().absarc(0, 0, 0.052, 0, Math.PI * 2, true))
      const bandRing = extrudeShape(
        bandShape,
        0.016,
        new THREE.MeshBasicMaterial({
          color: 0xf3e8ff,
          transparent: true,
          opacity: 0.9,
          side: THREE.DoubleSide,
        }),
      )
      bandRing.rotation.x = Math.PI / 2

      batonUnit.add(coreCylinder, bandRing)
      batonUnit.rotation.z = 0.45

      // Aro de destello al recibir el testigo en la mano derecha
      const catchShape = new THREE.Shape().absarc(0, 0, 0.22, 0, Math.PI * 2, false)
      catchShape.holes.push(new THREE.Path().absarc(0, 0, 0.185, 0, Math.PI * 2, true))
      const catchRing = extrudeShape(
        catchShape,
        0.014,
        new THREE.MeshBasicMaterial({
          color: 0xd8b4fe,
          transparent: true,
          opacity: 0.85,
          side: THREE.DoubleSide,
        }),
      )
      catchRing.name = 'relayCatchRing'

      relayGroup.add(batonUnit, catchRing)
      group.add(relayGroup)
      break
    }
    case 'eco-leaf': {
      const ecoGroup = new THREE.Group()
      ecoGroup.name = 'ecoLeaf'

      // Hoja orgánica 3D con nervadura central en verde lima
      const leafShape = new THREE.Shape()
      leafShape.moveTo(0, -0.17)
      leafShape.quadraticCurveTo(0.15, -0.05, 0.12, 0.1)
      leafShape.quadraticCurveTo(0.06, 0.19, 0, 0.24)
      leafShape.quadraticCurveTo(-0.06, 0.19, -0.12, 0.1)
      leafShape.quadraticCurveTo(-0.15, -0.05, 0, -0.17)
      leafShape.closePath()

      const leafMat = plasticMat(0x84cc16)
      leafMat.emissive = new THREE.Color(0x65a30d).multiplyScalar(0.35)
      const leafMesh = extrudeShape(leafShape, 0.022, leafMat)
      leafMesh.rotation.z = -0.32

      // Nervadura central y tallo
      const veinMat = plasticMat(0xd9f99d)
      const vein = new THREE.Mesh(new THREE.BoxGeometry(0.014, 0.34, 0.028), veinMat)
      vein.position.y = 0.01
      leafMesh.add(vein)

      // Anillo suave de eficiencia energética alrededor de la hoja
      const ringShape = new THREE.Shape().absarc(0, 0, 0.28, 0, Math.PI * 2, false)
      ringShape.holes.push(new THREE.Path().absarc(0, 0, 0.248, 0, Math.PI * 2, true))
      const ecoRing = extrudeShape(
        ringShape,
        0.012,
        new THREE.MeshBasicMaterial({
          color: 0xbef264,
          transparent: true,
          opacity: 0.68,
          side: THREE.DoubleSide,
        }),
      )
      ecoRing.name = 'ecoPulseRing'

      ecoGroup.add(leafMesh, ecoRing)
      ecoGroup.position.set(0.84, 0.66, 0.44)
      group.add(ecoGroup)
      break
    }
    case 'upgrade-chevrons': {
      const escGroup = new THREE.Group()
      escGroup.name = 'upgradeChevrons'

      // Tres galones ascendentes (▲ ▲ ▲) que se iluminan secuencialmente de abajo arriba
      const chevronsGrp = new THREE.Group()
      chevronsGrp.name = 'chevronStack'
      for (let i = 0; i < 3; i++) {
        const chevShape = new THREE.Shape()
        const w = 0.22 - i * 0.015
        const h = 0.11
        const thick = 0.044
        chevShape.moveTo(-w, 0)
        chevShape.lineTo(0, h)
        chevShape.lineTo(w, 0)
        chevShape.lineTo(w - thick * 0.85, -thick * 0.7)
        chevShape.lineTo(0, h - thick * 1.15)
        chevShape.lineTo(-w + thick * 0.85, -thick * 0.7)
        chevShape.closePath()

        const mat = plasticMat(0xe879f9)
        mat.emissive = new THREE.Color(0xc026d3)
        mat.emissiveIntensity = 0.35
        mat.transparent = true

        const mesh = extrudeShape(chevShape, 0.024, mat)
        mesh.position.set(0, -0.16 + i * 0.16, 0)
        mesh.userData = { idx: i }
        chevronsGrp.add(mesh)
      }

      // Anillo de pulso ascendente de subida de nivel
      const boostShape = new THREE.Shape().absarc(0, 0, 0.42, 0, Math.PI * 2, false)
      boostShape.holes.push(new THREE.Path().absarc(0, 0, 0.385, 0, Math.PI * 2, true))
      const boostRing = extrudeShape(
        boostShape,
        0.014,
        new THREE.MeshBasicMaterial({
          color: 0xf0abfc,
          transparent: true,
          opacity: 0.75,
          side: THREE.DoubleSide,
        }),
      )
      boostRing.rotation.x = Math.PI / 2
      boostRing.name = 'escalateBoostRing'

      escGroup.add(chevronsGrp, boostRing)
      escGroup.position.set(0, 0.08, 1.18)
      group.add(escGroup)
      break
    }
    case 'token-stream': {
      const streamGroup = new THREE.Group()
      streamGroup.name = 'tokenStream'

      // Aro emisor holográfico frente al visor
      const emitterShape = new THREE.Shape().absarc(0, 0, 0.3, 0, Math.PI * 2, false)
      emitterShape.holes.push(new THREE.Path().absarc(0, 0, 0.268, 0, Math.PI * 2, true))
      const emitterRing = extrudeShape(
        emitterShape,
        0.015,
        new THREE.MeshBasicMaterial({
          color: 0x22d3ee,
          transparent: true,
          opacity: 0.52,
          side: THREE.DoubleSide,
        }),
      )
      emitterRing.name = 'streamEmitter'
      streamGroup.add(emitterRing)

      // Cascada de 16 bloques/tokens luminosos brotando hacia adelante
      const tokenColors = [0x22d3ee, 0x67e8f9, 0x38bdf8, 0xa5f3fc]
      const tokensGrp = new THREE.Group()
      tokensGrp.name = 'streamTokens'
      for (let i = 0; i < 16; i++) {
        const col = tokenColors[i % tokenColors.length]
        const mat = new THREE.MeshBasicMaterial({
          color: col,
          transparent: true,
          opacity: 0.92,
        })
        const w = 0.062 + (i % 3) * 0.028
        const tok = new THREE.Mesh(new THREE.BoxGeometry(w, 0.034, 0.034), mat)
        tok.userData = {
          phase: i / 16,
          laneX: ((i % 3) - 1) * 0.14 + ((i % 2) - 0.5) * 0.038,
          laneY: (Math.floor(i / 4) % 2 === 0 ? 1 : -1) * (0.052 + (i % 3) * 0.04),
          spinDir: i % 2 === 0 ? 1 : -1,
        }
        tokensGrp.add(tok)
      }
      streamGroup.add(tokensGrp)
      streamGroup.position.set(0, -0.04, 1.08)
      group.add(streamGroup)
      break
    }
    case 'sync-arrows': {
      const syncGroup = new THREE.Group()
      syncGroup.name = 'syncArrows'

      const skyMat = plasticMat(0x38bdf8)
      skyMat.emissive = new THREE.Color(0x0284c7).multiplyScalar(0.45)
      const iceMat = plasticMat(0x7dd3fc)
      iceMat.emissive = new THREE.Color(0x38bdf8).multiplyScalar(0.35)

      // Dos flechas curvas opuestas formando un ciclo continuo de sincronización
      ;[0, Math.PI].forEach((rotOffset, idx) => {
        const arm = new THREE.Group()
        arm.rotation.z = rotOffset

        const rArc = 0.25
        const aStart = 0.22
        const aEnd = Math.PI - 0.36
        const pts = []
        for (let i = 0; i <= 32; i++) {
          const a = aStart + (i / 32) * (aEnd - aStart)
          pts.push(new THREE.Vector3(Math.cos(a) * rArc, Math.sin(a) * rArc, 0))
        }
        const mat = idx === 0 ? skyMat : iceMat
        const tube = new THREE.Mesh(
          new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 32, 0.028, 12),
          mat,
        )
        const tip = new THREE.Mesh(new THREE.ConeGeometry(0.068, 0.125, 18), mat)
        tip.position.copy(pts[pts.length - 1])
        tip.rotation.z = aEnd

        arm.add(tube, tip)
        syncGroup.add(arm)
      })

      // Dos pulsos luminosos que orbitan intercambiando datos entre ramas
      const pulseA = new THREE.Mesh(
        new THREE.SphereGeometry(0.036, 16, 16),
        new THREE.MeshBasicMaterial({ color: 0xe0f2fe }),
      )
      pulseA.name = 'syncPulseA'
      const pulseB = new THREE.Mesh(
        new THREE.SphereGeometry(0.036, 16, 16),
        new THREE.MeshBasicMaterial({ color: 0x38bdf8 }),
      )
      pulseB.name = 'syncPulseB'

      syncGroup.add(pulseA, pulseB)
      syncGroup.position.set(0, -0.08, 1.02)
      group.add(syncGroup)
      break
    }
    case 'holo-wireframe': {
      const holoGroup = new THREE.Group()
      holoGroup.name = 'holoWireframe'

      // Engranaje / cubo holográfico fantasma en wireframe azul hielo
      const gearUnit = new THREE.Group()
      gearUnit.name = 'holoGear'

      const ghostMat = new THREE.MeshBasicMaterial({
        color: 0x60a5fa,
        transparent: true,
        opacity: 0.14,
        depthWrite: false,
        side: THREE.DoubleSide,
      })
      const wireMat = new THREE.LineBasicMaterial({
        color: 0x93c5fd,
        transparent: true,
        opacity: 0.92,
      })

      // Engranaje 3D extruido con dientes
      const gearShape = new THREE.Shape()
      const teeth = 8
      const rInner = 0.13,
        rOuter = 0.18
      for (let i = 0; i < teeth * 4; i++) {
        const a = (i / (teeth * 4)) * Math.PI * 2
        const mod = i % 4
        const r = mod === 1 || mod === 2 ? rOuter : rInner
        const px = Math.cos(a) * r,
          py = Math.sin(a) * r
        if (i === 0) gearShape.moveTo(px, py)
        else gearShape.lineTo(px, py)
      }
      gearShape.closePath()
      gearShape.holes.push(new THREE.Path().absarc(0, 0, 0.055, 0, Math.PI * 2, true))

      const gearGeo = new THREE.ExtrudeGeometry(gearShape, {
        depth: 0.06,
        bevelEnabled: false,
      }).translate(0, 0, -0.03)
      const gearGhost = new THREE.Mesh(gearGeo, ghostMat)
      const gearEdges = new THREE.LineSegments(new THREE.EdgesGeometry(gearGeo, 18), wireMat)
      gearUnit.add(gearGhost, gearEdges)

      // Anillo orbital discontinuo alrededor del holograma
      const orbitGroup = new THREE.Group()
      orbitGroup.name = 'holoDashRing'
      const segs = 12
      for (let i = 0; i < segs; i++) {
        const a0 = (i / segs) * Math.PI * 2 + 0.06
        const a1 = ((i + 0.55) / segs) * Math.PI * 2
        const dashShape = new THREE.Shape()
        dashShape.absarc(0, 0, 0.255, a0, a1, false)
        dashShape.absarc(0, 0, 0.238, a1, a0, true)
        dashShape.closePath()
        orbitGroup.add(
          extrudeShape(
            dashShape,
            0.008,
            new THREE.MeshBasicMaterial({
              color: 0xbae6fd,
              transparent: true,
              opacity: 0.78,
              side: THREE.DoubleSide,
            }),
          ),
        )
      }
      orbitGroup.rotation.x = Math.PI / 2 - 0.35

      // Dos haces discontinuos de proyección desde el visor hacia el holograma
      const raysGroup = new THREE.Group()
      raysGroup.name = 'holoProjectRays'
      ;[-1, 1].forEach((side) => {
        for (let d = 0; d < 4; d++) {
          const dash = new THREE.Mesh(
            new THREE.BoxGeometry(0.012, 0.012, 0.055),
            new THREE.MeshBasicMaterial({ color: 0x93c5fd, transparent: true, opacity: 0.65 }),
          )
          dash.userData = { side, idx: d }
          raysGroup.add(dash)
        }
      })

      holoGroup.add(gearUnit, orbitGroup, raysGroup)
      holoGroup.position.set(0.42, 0.08, 1.24)
      group.add(holoGroup)
      break
    }
    case 'pr-badge': {
      const prGroup = new THREE.Group()
      prGroup.name = 'prBadge'

      const emeraldMat = plasticMat(0x10b981)
      emeraldMat.emissive = new THREE.Color(0x059669).multiplyScalar(0.5)
      const mintMat = plasticMat(0x86efac)
      mintMat.emissive = new THREE.Color(0x22c55e).multiplyScalar(0.35)

      // Anillo exterior holográfico
      const ringShape = new THREE.Shape().absarc(0, 0, 0.25, 0, Math.PI * 2, false)
      ringShape.holes.push(new THREE.Path().absarc(0, 0, 0.21, 0, Math.PI * 2, true))
      const ringMesh = extrudeShape(
        ringShape,
        0.022,
        new THREE.MeshBasicMaterial({
          color: 0x4ade80,
          transparent: true,
          opacity: 0.85,
          side: THREE.DoubleSide,
        }),
      )

      // Icono 3D de Pull Request (dos nodos verticales a la izquierda + rama curva con check)
      const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.016, 0.016, 0.22, 12), mintMat)
      trunk.position.set(-0.075, 0, 0.015)
      const nodeTopL = new THREE.Mesh(new THREE.SphereGeometry(0.036, 16, 16), emeraldMat)
      nodeTopL.position.set(-0.075, 0.11, 0.02)
      const nodeBotL = new THREE.Mesh(new THREE.SphereGeometry(0.036, 16, 16), emeraldMat)
      nodeBotL.position.set(-0.075, -0.11, 0.02)

      const branchCurve = new THREE.CatmullRomCurve3([
        new THREE.Vector3(-0.075, 0.09, 0.015),
        new THREE.Vector3(0.04, 0.08, 0.015),
        new THREE.Vector3(0.075, -0.02, 0.015),
      ])
      const branchTube = new THREE.Mesh(new THREE.TubeGeometry(branchCurve, 20, 0.015, 10), mintMat)
      const nodeBotR = new THREE.Mesh(new THREE.SphereGeometry(0.042, 16, 16), emeraldMat)
      nodeBotR.position.set(0.075, -0.06, 0.02)

      // Anillo expansivo de celebración de PR abierta
      const waveShape = new THREE.Shape().absarc(0, 0, 0.29, 0, Math.PI * 2, false)
      waveShape.holes.push(new THREE.Path().absarc(0, 0, 0.255, 0, Math.PI * 2, true))
      const pulseRing = extrudeShape(
        waveShape,
        0.012,
        new THREE.MeshBasicMaterial({
          color: 0x86efac,
          transparent: true,
          opacity: 0.8,
          side: THREE.DoubleSide,
        }),
      )
      pulseRing.name = 'prPulseRing'

      prGroup.add(ringMesh, trunk, nodeTopL, nodeBotL, branchTube, nodeBotR, pulseRing)
      prGroup.position.set(0.86, 0.88, 0.42)
      group.add(prGroup)
      break
    }
    case 'context-press': {
      const pressGroup = new THREE.Group()
      pressGroup.name = 'contextPress'

      // 6 bloques dispersos de contexto que se comprimen hacia el centro
      const shardsGrp = new THREE.Group()
      shardsGrp.name = 'contextShards'
      const shardColors = [0x818cf8, 0xa5b4fc, 0x38bdf8, 0xc084fc, 0x6366f1, 0x93c5fd]
      const dirs = [
        [-0.46, 0.22, 0.04],
        [0.46, 0.22, -0.04],
        [-0.52, -0.02, -0.05],
        [0.52, -0.02, 0.05],
        [-0.42, -0.24, 0.03],
        [0.42, -0.24, -0.03],
      ]
      dirs.forEach((d, idx) => {
        const mat = plasticMat(shardColors[idx])
        mat.emissive = new THREE.Color(shardColors[idx]).multiplyScalar(0.35)
        mat.transparent = true
        const shard = new THREE.Mesh(new THREE.BoxGeometry(0.105, 0.085, 0.085), mat)
        shard.userData = { dir: d, idx }
        shardsGrp.add(shard)
      })

      // Cápsula brillante central resultante de la compactación
      const capsuleMat = plasticMat(0xe0e7ff)
      capsuleMat.emissive = new THREE.Color(0x6366f1)
      capsuleMat.emissiveIntensity = 0.85
      const capsule = new THREE.Mesh(new THREE.CapsuleGeometry(0.085, 0.11, 12, 24), capsuleMat)
      capsule.name = 'compactCapsule'
      capsule.rotation.z = Math.PI / 2

      // Anillo de pulso cuando se sella la cápsula compactada
      const pulseShape = new THREE.Shape().absarc(0, 0, 0.3, 0, Math.PI * 2, false)
      pulseShape.holes.push(new THREE.Path().absarc(0, 0, 0.255, 0, Math.PI * 2, true))
      const pulseRing = extrudeShape(
        pulseShape,
        0.015,
        new THREE.MeshBasicMaterial({
          color: 0xa5b4fc,
          transparent: true,
          opacity: 0.85,
          side: THREE.DoubleSide,
        }),
      )
      pulseRing.name = 'compactPulseRing'

      pressGroup.add(shardsGrp, capsule, pulseRing)
      pressGroup.position.set(0, -0.1, 0.96)
      group.add(pressGroup)
      break
    }
    case 'git-conflict': {
      const confGroup = new THREE.Group()
      confGroup.name = 'gitConflict'

      const leftMat = plasticMat(0x38bdf8)
      leftMat.emissive = new THREE.Color(0x0284c7).multiplyScalar(0.4)
      const rightMat = plasticMat(0xc084fc)
      rightMat.emissive = new THREE.Color(0x9333ea).multiplyScalar(0.4)

      const leftNode = new THREE.Mesh(new THREE.SphereGeometry(0.095, 24, 24), leftMat)
      leftNode.name = 'confLeft'
      const rightNode = new THREE.Mesh(new THREE.SphereGeometry(0.095, 24, 24), rightMat)
      rightNode.name = 'confRight'

      const leftTrail = new THREE.Mesh(new THREE.CylinderGeometry(0.022, 0.022, 0.36, 16), leftMat)
      leftTrail.name = 'confLeftTrail'
      const rightTrail = new THREE.Mesh(
        new THREE.CylinderGeometry(0.022, 0.022, 0.36, 16),
        rightMat,
      )
      rightTrail.name = 'confRightTrail'

      // Chispas naranjas que estallan al chocar ambas ramas
      const sparksGrp = new THREE.Group()
      sparksGrp.name = 'conflictSparks'
      for (let i = 0; i < 8; i++) {
        const sp = new THREE.Mesh(
          new THREE.BoxGeometry(0.09, 0.024, 0.024),
          new THREE.MeshBasicMaterial({
            color: i % 2 === 0 ? 0xfb923c : 0xfef08a,
            transparent: true,
            opacity: 0.95,
          }),
        )
        sp.userData = { ang: (i / 8) * Math.PI * 2 + 0.2 }
        sparksGrp.add(sp)
      }

      const impactShape = new THREE.Shape().absarc(0, 0, 0.28, 0, Math.PI * 2, false)
      impactShape.holes.push(new THREE.Path().absarc(0, 0, 0.23, 0, Math.PI * 2, true))
      const impactRing = extrudeShape(
        impactShape,
        0.016,
        new THREE.MeshBasicMaterial({
          color: 0xfb923c,
          transparent: true,
          opacity: 0.9,
          side: THREE.DoubleSide,
        }),
      )
      impactRing.name = 'conflictImpactRing'

      confGroup.add(leftTrail, rightTrail, leftNode, rightNode, sparksGrp, impactRing)
      confGroup.position.set(0, -0.1, 0.94)
      group.add(confGroup)
      break
    }
    case 'layer-scanner': {
      const idxGrp = new THREE.Group()
      idxGrp.name = 'layerScanner'

      const layersGrp = new THREE.Group()
      layersGrp.name = 'indexLayers'
      const layerYs = [0.22, 0.0, -0.22]
      layerYs.forEach((ly, i) => {
        const layerUnit = new THREE.Group()
        layerUnit.position.y = ly

        const disc = new THREE.Mesh(
          new THREE.CylinderGeometry(0.32, 0.32, 0.018, 36),
          new THREE.MeshBasicMaterial({
            color: 0x0d9488,
            transparent: true,
            opacity: 0.24,
            depthWrite: false,
          }),
        )
        disc.name = 'layerDisc'

        const ringShape = new THREE.Shape().absarc(0, 0, 0.33, 0, Math.PI * 2, false)
        ringShape.holes.push(new THREE.Path().absarc(0, 0, 0.305, 0, Math.PI * 2, true))
        const rim = extrudeShape(
          ringShape,
          0.016,
          new THREE.MeshBasicMaterial({
            color: 0x5eead4,
            transparent: true,
            opacity: 0.65,
            side: THREE.DoubleSide,
          }),
        )
        rim.rotation.x = Math.PI / 2
        rim.name = 'layerRim'

        // Pequeños nodos de código sobre cada capa
        for (let k = 0; k < 4; k++) {
          const a = (k / 4) * Math.PI * 2 + i * 0.6
          const dot = new THREE.Mesh(
            new THREE.BoxGeometry(0.055, 0.016, 0.032),
            new THREE.MeshBasicMaterial({ color: 0x99f6e4, transparent: true, opacity: 0.8 }),
          )
          dot.position.set(Math.cos(a) * 0.16, 0.012, Math.sin(a) * 0.16)
          dot.rotation.y = -a
          layerUnit.add(dot)
        }

        layerUnit.add(disc, rim)
        layersGrp.add(layerUnit)
      })

      // Anillo láser cian que barre verticalmente las 3 capas
      const laserShape = new THREE.Shape().absarc(0, 0, 0.38, 0, Math.PI * 2, false)
      laserShape.holes.push(new THREE.Path().absarc(0, 0, 0.352, 0, Math.PI * 2, true))
      const scanRing = extrudeShape(
        laserShape,
        0.018,
        new THREE.MeshBasicMaterial({
          color: 0x2dd4bf,
          transparent: true,
          opacity: 0.95,
          side: THREE.DoubleSide,
        }),
      )
      scanRing.rotation.x = Math.PI / 2
      scanRing.name = 'indexLaserRing'

      idxGrp.add(layersGrp, scanRing)
      idxGrp.position.set(0, -0.02, 1.02)
      idxGrp.rotation.x = 0.22
      group.add(idxGrp)
      break
    }
    case 'hex-shield': {
      const shieldGrp = new THREE.Group()
      shieldGrp.name = 'hexShield'

      const hexShape = new THREE.Shape()
      const rOuter = 0.44
      for (let i = 0; i < 6; i++) {
        const a = (i * Math.PI) / 3 + Math.PI / 6
        const px = Math.cos(a) * rOuter
        const py = Math.sin(a) * rOuter
        if (i === 0) hexShape.moveTo(px, py)
        else hexShape.lineTo(px, py)
      }
      hexShape.closePath()

      const glassMat = new THREE.MeshBasicMaterial({
        color: 0xf43f5e,
        transparent: true,
        opacity: 0.2,
        side: THREE.DoubleSide,
        depthWrite: false,
      })
      const plate = extrudeShape(hexShape, 0.014, glassMat)
      plate.name = 'shieldPlate'

      const rimShape = new THREE.Shape()
      for (let i = 0; i < 6; i++) {
        const a = (i * Math.PI) / 3 + Math.PI / 6
        const px = Math.cos(a) * (rOuter + 0.025)
        const py = Math.sin(a) * (rOuter + 0.025)
        if (i === 0) rimShape.moveTo(px, py)
        else rimShape.lineTo(px, py)
      }
      rimShape.closePath()
      const rimHole = new THREE.Path()
      for (let i = 0; i < 6; i++) {
        const a = (i * Math.PI) / 3 + Math.PI / 6
        const px = Math.cos(a) * (rOuter - 0.015)
        const py = Math.sin(a) * (rOuter - 0.015)
        if (i === 0) rimHole.moveTo(px, py)
        else rimHole.lineTo(px, py)
      }
      rimHole.closePath()
      rimShape.holes.push(rimHole)
      const rim = extrudeShape(
        rimShape,
        0.026,
        new THREE.MeshBasicMaterial({
          color: 0xfda4af,
          transparent: true,
          opacity: 0.92,
          side: THREE.DoubleSide,
        }),
      )

      // Emblema central de alerta / bloqueo (barra vertical + punto)
      const emblemMat = new THREE.MeshBasicMaterial({
        color: 0xffe4e6,
        transparent: true,
        opacity: 0.95,
      })
      const bar = new THREE.Mesh(new THREE.BoxGeometry(0.052, 0.2, 0.028), emblemMat)
      bar.position.y = 0.05
      const dot = new THREE.Mesh(new THREE.BoxGeometry(0.052, 0.052, 0.028), emblemMat)
      dot.position.y = -0.11

      // Onda de impacto hexagonal expansiva
      const waveRing = extrudeShape(
        rimShape,
        0.012,
        new THREE.MeshBasicMaterial({
          color: 0xfb7185,
          transparent: true,
          opacity: 0.85,
          side: THREE.DoubleSide,
        }),
      )
      waveRing.name = 'shieldShockwave'

      shieldGrp.add(plate, rim, bar, dot, waveRing)
      shieldGrp.position.set(0, 0.02, 1.06)
      group.add(shieldGrp)
      break
    }
    case 'balance-scale': {
      const scaleGroup = new THREE.Group()
      scaleGroup.name = 'balanceScale'

      const goldMat = plasticMat(0xfbbf24)
      goldMat.emissive = new THREE.Color(0xd97706).multiplyScalar(0.25)
      const holoMat = new THREE.MeshBasicMaterial({
        color: 0xfde047,
        transparent: true,
        opacity: 0.85,
        side: THREE.DoubleSide,
      })

      const pillar = new THREE.Mesh(new THREE.CylinderGeometry(0.022, 0.03, 0.5, 16), holoMat)
      pillar.position.y = -0.05
      const baseDisc = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.18, 0.024, 28), goldMat)
      baseDisc.position.y = -0.3

      const armGroup = new THREE.Group()
      armGroup.name = 'scaleArm'
      armGroup.position.y = 0.19
      const crossBar = new THREE.Mesh(new THREE.BoxGeometry(0.94, 0.028, 0.028), goldMat)
      const pivotSphere = new THREE.Mesh(new THREE.SphereGeometry(0.046, 16, 16), goldMat)
      armGroup.add(crossBar, pivotSphere)

      const armSpan = 0.42
      ;[-1, 1].forEach((side, idx) => {
        const panUnit = new THREE.Group()
        panUnit.name = idx === 0 ? 'scalePanA' : 'scalePanB'
        panUnit.position.set(side * armSpan, 0, 0)

        const cord = new THREE.Mesh(new THREE.CylinderGeometry(0.008, 0.008, 0.2, 8), holoMat)
        cord.position.y = -0.1

        const dishMat = plasticMat(0xfde047)
        dishMat.emissive = new THREE.Color(0xca8a04).multiplyScalar(0.3)
        const dish = new THREE.Mesh(new THREE.CylinderGeometry(0.165, 0.115, 0.028, 28), dishMat)
        dish.name = 'panDish'
        dish.position.y = -0.2

        const orbMat = plasticMat(idx === 0 ? 0x38bdf8 : 0xc084fc)
        orbMat.emissive = new THREE.Color(idx === 0 ? 0x0284c7 : 0x9333ea).multiplyScalar(0.4)
        const payload = new THREE.Mesh(new THREE.BoxGeometry(0.088, 0.088, 0.088), orbMat)
        payload.name = 'panPayload'
        payload.position.y = -0.142

        const winRingShape = new THREE.Shape().absarc(0, 0, 0.205, 0, Math.PI * 2, false)
        winRingShape.holes.push(new THREE.Path().absarc(0, 0, 0.172, 0, Math.PI * 2, true))
        const winRing = extrudeShape(
          winRingShape,
          0.012,
          new THREE.MeshBasicMaterial({
            color: 0x10b981,
            transparent: true,
            opacity: 0,
            side: THREE.DoubleSide,
          }),
        )
        winRing.name = 'panWinRing'
        winRing.rotation.x = Math.PI / 2
        winRing.position.y = -0.195

        panUnit.add(cord, dish, payload, winRing)
        armGroup.add(panUnit)
      })

      scaleGroup.add(pillar, baseDisc, armGroup)
      scaleGroup.position.set(0, -0.12, 1.02)
      group.add(scaleGroup)
      break
    }
    case 'sandbox-cube': {
      const boxGroup = new THREE.Group()
      boxGroup.name = 'sandboxCube'

      const size = 0.44
      const cubeGeo = new THREE.BoxGeometry(size, size, size)
      const wallMat = new THREE.MeshBasicMaterial({
        color: 0x10b981,
        transparent: true,
        opacity: 0.12,
        side: THREE.DoubleSide,
        depthWrite: false,
      })
      const walls = new THREE.Mesh(cubeGeo, wallMat)
      walls.name = 'sandboxWalls'

      const edges = new THREE.LineSegments(
        new THREE.EdgesGeometry(cubeGeo),
        new THREE.LineBasicMaterial({ color: 0x6ee7b7, transparent: true, opacity: 0.9 }),
      )
      edges.name = 'sandboxEdges'

      const cornersGrp = new THREE.Group()
      cornersGrp.name = 'sandboxCorners'
      const cornerMat = plasticMat(0x34d399)
      cornerMat.emissive = new THREE.Color(0x059669).multiplyScalar(0.5)
      const half = size * 0.5
      for (const sx of [-1, 1]) {
        for (const sy of [-1, 1]) {
          for (const sz of [-1, 1]) {
            const dot = new THREE.Mesh(new THREE.SphereGeometry(0.026, 12, 12), cornerMat)
            dot.position.set(sx * half, sy * half, sz * half)
            cornersGrp.add(dot)
          }
        }
      }

      const scanPlane = new THREE.Mesh(
        new THREE.PlaneGeometry(size * 0.94, size * 0.94).rotateX(Math.PI / 2),
        new THREE.MeshBasicMaterial({
          color: 0xa7f3d0,
          transparent: true,
          opacity: 0.35,
          side: THREE.DoubleSide,
          depthWrite: false,
        }),
      )
      scanPlane.name = 'sandboxScanPlane'

      const agentMat = plasticMat(0x6ee7b7)
      agentMat.emissive = new THREE.Color(0x10b981)
      agentMat.emissiveIntensity = 0.85
      const agentOrb = new THREE.Mesh(new THREE.SphereGeometry(0.052, 24, 24), agentMat)
      agentOrb.name = 'sandboxAgentOrb'

      boxGroup.add(walls, edges, cornersGrp, scanPlane, agentOrb)
      boxGroup.position.set(0, -0.06, 0.98)
      group.add(boxGroup)
      break
    }
    case 'model-router': {
      const routerGroup = new THREE.Group()
      routerGroup.name = 'modelRouter'

      const trackShape = new THREE.Shape().absarc(0, 0, 0.44, 0, Math.PI * 2, false)
      trackShape.holes.push(new THREE.Path().absarc(0, 0, 0.415, 0, Math.PI * 2, true))
      const trackRing = extrudeShape(
        trackShape,
        0.012,
        new THREE.MeshBasicMaterial({
          color: 0x67e8f9,
          transparent: true,
          opacity: 0.38,
          side: THREE.DoubleSide,
        }),
      )
      trackRing.rotation.x = Math.PI / 2 - 0.35
      routerGroup.add(trackRing)

      const haloShape = new THREE.Shape().absarc(0, 0, 0.145, 0, Math.PI * 2, false)
      haloShape.holes.push(new THREE.Path().absarc(0, 0, 0.115, 0, Math.PI * 2, true))
      const selectHalo = extrudeShape(
        haloShape,
        0.016,
        new THREE.MeshBasicMaterial({
          color: 0xffffff,
          transparent: true,
          opacity: 0.9,
          side: THREE.DoubleSide,
        }),
      )
      selectHalo.name = 'routerSelectHalo'
      routerGroup.add(selectHalo)

      const modelColors = [0x38bdf8, 0xfb923c, 0x4ade80]
      const nodesGrp = new THREE.Group()
      nodesGrp.name = 'routerNodes'
      for (let i = 0; i < 3; i++) {
        const mat = plasticMat(modelColors[i])
        mat.emissive = new THREE.Color(modelColors[i]).multiplyScalar(0.35)
        const orb = new THREE.Mesh(new THREE.SphereGeometry(0.072, 24, 24), mat)
        orb.userData = { idx: i, baseAng: (i / 3) * Math.PI * 2, colorHex: modelColors[i] }
        nodesGrp.add(orb)
      }
      routerGroup.add(nodesGrp)
      routerGroup.position.set(0, -0.16, 0.96)
      group.add(routerGroup)
      break
    }
    case 'battery-cooldown': {
      const battery = new THREE.Group()
      battery.name = 'cooldownBattery'

      const chromeMat = new THREE.MeshPhysicalMaterial({
        color: 0xdce4ee,
        metalness: 0.88,
        roughness: 0.22,
        clearcoat: 0.35,
      })
      const darkRimMat = new THREE.MeshStandardMaterial({
        color: 0x263143,
        metalness: 0.65,
        roughness: 0.35,
      })
      const glassMat = new THREE.MeshPhysicalMaterial({
        color: 0x334155,
        transparent: true,
        opacity: 0.44,
        roughness: 0.16,
        metalness: 0.08,
        clearcoat: 0.9,
        depthWrite: false,
        side: THREE.DoubleSide,
      })

      // Cilindro exterior de cristal ahumado
      battery.add(
        new THREE.Mesh(new THREE.CylinderGeometry(0.118, 0.118, 0.44, 48, 1, true), glassMat),
      )

      // Tapas metálicas superior e inferior con aro oscuro de sellado
      for (const y of [-0.236, 0.236]) {
        const cap = new THREE.Mesh(new THREE.CylinderGeometry(0.125, 0.125, 0.034, 48), chromeMat)
        cap.position.y = y
        battery.add(cap)

        const seam = new THREE.Mesh(new THREE.TorusGeometry(0.122, 0.0055, 10, 48), darkRimMat)
        seam.rotation.x = Math.PI / 2
        seam.position.y = y > 0 ? 0.219 : -0.219
        battery.add(seam)
      }

      // Borne positivo escalonado metálico en la parte superior
      const termBase = new THREE.Mesh(
        new THREE.CylinderGeometry(0.062, 0.068, 0.014, 32),
        darkRimMat,
      )
      termBase.position.y = 0.258
      const terminal = new THREE.Mesh(new THREE.CylinderGeometry(0.048, 0.05, 0.03, 32), chromeMat)
      terminal.position.y = 0.275
      battery.add(termBase, terminal)

      // Líquido energético principal con menisco ondulante
      const liquid = new THREE.Mesh(
        new THREE.CylinderGeometry(0.104, 0.104, 1, 48),
        new THREE.MeshStandardMaterial({
          color: 0xef4444,
          emissive: 0xef4444,
          emissiveIntensity: 0.45,
          roughness: 0.24,
          transparent: true,
          opacity: 0.92,
        }),
      )
      liquid.name = 'cooldownLiquid'
      liquid.scale.y = 0.001
      liquid.position.y = -0.2195
      liquid.userData.rest = new Float32Array(liquid.geometry.attributes.position.array)
      battery.add(liquid)

      // Núcleo luminoso interno para simular la emisión volumétrica de la pila
      const innerCore = new THREE.Mesh(
        new THREE.CylinderGeometry(0.058, 0.058, 1, 32),
        new THREE.MeshBasicMaterial({
          color: 0xffffff,
          transparent: true,
          opacity: 0.34,
          depthWrite: false,
        }),
      )
      innerCore.name = 'cooldownCore'
      innerCore.scale.y = 0.001
      innerCore.position.y = -0.2195
      battery.add(innerCore)

      // Menisco brillante superior
      const meniscusGeometry = new THREE.TorusGeometry(0.101, 0.0032, 10, 48)
      meniscusGeometry.rotateX(Math.PI / 2)
      const meniscus = new THREE.Mesh(
        meniscusGeometry,
        new THREE.MeshStandardMaterial({
          color: 0xffffff,
          emissive: 0xffffff,
          emissiveIntensity: 0.55,
          roughness: 0.15,
        }),
      )
      meniscus.name = 'cooldownMeniscus'
      meniscus.userData.rest = new Float32Array(meniscusGeometry.attributes.position.array)
      battery.add(meniscus)

      // Rayo holográfico al llegar al 100% de carga
      const boltShape = new THREE.Shape()
      boltShape.moveTo(0.02, 0.12)
      boltShape.lineTo(-0.06, 0.01)
      boltShape.lineTo(-0.01, 0.01)
      boltShape.lineTo(-0.04, -0.12)
      boltShape.lineTo(0.06, -0.01)
      boltShape.lineTo(0.01, -0.01)
      boltShape.closePath()
      const bolt = extrudeShape(
        boltShape,
        0.022,
        new THREE.MeshBasicMaterial({ color: 0x22c55e, transparent: true, opacity: 0.95 }),
      )
      bolt.name = 'cooldownBolt'
      bolt.position.set(0, 0.42, 0.02)
      bolt.visible = false
      battery.add(bolt)

      battery.position.set(0.94, 0.31, 0.4)
      group.add(battery)
      break
    }
    case 'rollback-arrow': {
      const arrow = new THREE.Group()
      arrow.name = 'rollbackArrow'
      const material = plasticMat(0xe97840)
      material.emissive = new THREE.Color(0xea580c).multiplyScalar(0.3)
      const points = []
      const startAngle = -Math.PI * 0.25
      const endAngle = startAngle + Math.PI * 1.58
      const arcRadius = 0.22
      const tubeRadius = 0.042
      for (let i = 0; i <= 64; i++) {
        const angle = startAngle + (i / 64) * (endAngle - startAngle)
        points.push(new THREE.Vector3(Math.cos(angle) * arcRadius, Math.sin(angle) * arcRadius, 0))
      }
      const tubeMesh = new THREE.Mesh(
        new THREE.TubeGeometry(new THREE.CatmullRomCurve3(points), 64, tubeRadius, 16),
        material,
      )
      const tailCap = new THREE.Mesh(new THREE.SphereGeometry(tubeRadius, 16, 16), material)
      tailCap.position.copy(points[0])
      const tip = new THREE.Mesh(new THREE.ConeGeometry(0.096, 0.17, 24), material)
      tip.position.copy(points[points.length - 1])
      tip.rotation.z = endAngle
      const spinner = new THREE.Group()
      spinner.name = 'rollbackSpinner'
      spinner.add(tubeMesh, tailCap, tip)
      arrow.add(spinner)
      arrow.position.set(0, 1.06, 0.18)
      arrow.rotation.x = -0.24
      group.add(arrow)
      break
    }
    case 'satellite-drones': {
      group.name = 'subagentsGroup'
      const visor = plasticMat(0x101527),
        white = plasticMat(0xf5f4ff)
      const eyeCanvas = document.createElement('canvas')
      eyeCanvas.width = eyeCanvas.height = 256
      const eyeCtx = eyeCanvas.getContext('2d')
      eyeCtx.lineCap = 'round'
      for (const [width, color, blur] of [
        [31, '#00d2ff', 24],
        [17, '#f4ffff', 5],
      ]) {
        eyeCtx.strokeStyle = color
        eyeCtx.lineWidth = width
        eyeCtx.shadowColor = '#00dcff'
        eyeCtx.shadowBlur = blur
        eyeCtx.beginPath()
        for (const x of [88, 168]) {
          eyeCtx.moveTo(x, 96)
          eyeCtx.lineTo(x, 160)
        }
        eyeCtx.stroke()
      }
      const eyeTexture = new THREE.CanvasTexture(eyeCanvas)
      const glow = new THREE.MeshBasicMaterial({
        map: eyeTexture,
        transparent: true,
        depthWrite: false,
        toneMapped: false,
      })
      for (let i = 0; i < 2; i++) {
        const drone = new THREE.Group()
        const shell = plasticMat(i === 0 ? 0xa5b4fc : 0xf0abdf)
        const body = new THREE.Mesh(createBodyGeometry(), shell)
        body.scale.setScalar(0.21)
        drone.add(body)
        const rim = new THREE.Mesh(new THREE.TorusGeometry(0.078, 0.011, 10, 32), white)
        rim.position.set(0, -0.008, 0.115)
        drone.add(rim)
        const face = new THREE.Mesh(new THREE.CircleGeometry(0.072, 32), visor)
        face.position.set(0, -0.008, 0.117)
        drone.add(face)
        const eyes = new THREE.Mesh(new THREE.PlaneGeometry(0.135, 0.135), glow)
        eyes.position.set(0, -0.008, 0.125)
        drone.add(eyes)
        for (const side of [-1, 1]) {
          const hand = new THREE.Mesh(new THREE.SphereGeometry(0.05, 16, 12), white)
          hand.position.set(side * 0.19, -0.085, 0.025)
          drone.add(hand)
        }
        group.add(drone)
      }
      break
    }
    case 'memory-file': {
      const recallGroup = new THREE.Group()
      recallGroup.name = 'recallGroup'

      const starShape = new THREE.Shape()
      for (let i = 0; i < 8; i++) {
        const angle = (i * Math.PI) / 4
        const radius = i % 2 ? 0.028 : 0.115
        starShape[i ? 'lineTo' : 'moveTo'](Math.cos(angle) * radius, Math.sin(angle) * radius)
      }
      starShape.closePath()
      const sparkMat = new THREE.MeshStandardMaterial({
        color: 0xfef08a,
        emissive: 0xf59e0b,
        emissiveIntensity: 0.7,
        roughness: 0.3,
      })
      const spark = extrudeShape(starShape, 0.024, sparkMat)
      spark.name = 'recallSpark'
      spark.position.set(0.68, 0.72, 0.38)
      recallGroup.add(spark)

      const nodesGrp = new THREE.Group()
      nodesGrp.name = 'recallNodes'
      const nodeColors = [0xc4b5fd, 0xa78bfa, 0xfde047, 0x93c5fd]
      for (let i = 0; i < 4; i++) {
        const m = plasticMat(nodeColors[i])
        m.emissive = new THREE.Color(nodeColors[i]).multiplyScalar(0.35)
        m.transparent = true
        const orb = new THREE.Mesh(new THREE.OctahedronGeometry(0.052, 0), m)
        orb.userData = { angle: (i / 4) * Math.PI * 2, phase: i * 0.25 }
        nodesGrp.add(orb)
      }
      recallGroup.add(nodesGrp)
      group.add(recallGroup)
      break
    }
    case 'check-document': {
      const paper = new THREE.Group()
      paper.name = 'checkDoc'
      const white = plasticMat(0xf8fafc)
      const ink = plasticMat(0x64748b)
      const highlight = new THREE.MeshBasicMaterial({ color: 0xfef3c7 })
      const amber = plasticMat(0xf59e0b)
      amber.emissive = new THREE.Color(0xd97706).multiplyScalar(0.45)

      const sheet = new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.52, 0.03), white)
      paper.add(sheet)

      const highlightBar = new THREE.Mesh(new THREE.BoxGeometry(0.36, 0.34, 0.009), highlight)
      highlightBar.position.set(0, 0.04, 0.019)
      paper.add(highlightBar)

      for (let i = 0; i < 3; i++) {
        const line = new THREE.Mesh(new THREE.BoxGeometry(0.26, 0.02, 0.01), ink)
        line.position.set(0, 0.12 - i * 0.08, 0.021)
        paper.add(line)
      }

      const badge = new THREE.Mesh(
        new THREE.CylinderGeometry(0.088, 0.088, 0.024, 32).rotateX(Math.PI / 2),
        amber,
      )
      badge.name = 'docAlertBadge'
      badge.position.set(0.16, -0.18, 0.03)
      const mark = new THREE.Mesh(new THREE.BoxGeometry(0.022, 0.066, 0.012), white)
      mark.position.set(0, 0.015, 0.016)
      const dot = new THREE.Mesh(new THREE.BoxGeometry(0.022, 0.022, 0.012), white)
      dot.position.set(0, -0.04, 0.016)
      badge.add(mark, dot)
      paper.add(badge)

      paper.position.set(0.94, 0.62, 0.56)
      paper.rotation.y = -0.34
      group.add(paper)
      break
    }
    case 'clock': {
      const clock = new THREE.Group()
      clock.name = 'extClock'
      const frame = plasticMat(0x475569)
      const face = plasticMat(0xf0f9ff)
      const disc = new THREE.Mesh(
        new THREE.CylinderGeometry(0.17, 0.17, 0.038, 48).rotateX(Math.PI / 2),
        face,
      )
      clock.add(disc)
      clock.add(new THREE.Mesh(new THREE.TorusGeometry(0.175, 0.02, 14, 48), frame))

      const pulseRingShape = new THREE.Shape().absarc(0, 0, 0.26, 0, Math.PI * 2, false)
      pulseRingShape.holes.push(new THREE.Path().absarc(0, 0, 0.235, 0, Math.PI * 2, true))
      const pulseRing = extrudeShape(
        pulseRingShape,
        0.012,
        new THREE.MeshBasicMaterial({
          color: 0x60a5fa,
          transparent: true,
          opacity: 0.7,
          side: THREE.DoubleSide,
        }),
      )
      pulseRing.name = 'extPulseRing'
      clock.add(pulseRing)

      for (let i = 0; i < 12; i++) {
        const angle = (i * Math.PI) / 6
        const tick = new THREE.Mesh(new THREE.BoxGeometry(0.01, 0.024, 0.008), frame)
        tick.position.set(Math.sin(angle) * 0.14, Math.cos(angle) * 0.14, 0.024)
        tick.rotation.z = -angle
        clock.add(tick)
      }
      const hand = new THREE.Group()
      hand.name = 'clockHand'
      const bar = new THREE.Mesh(
        new THREE.BoxGeometry(0.012, 0.115, 0.012),
        new THREE.MeshBasicMaterial({ color: 0x2563eb }),
      )
      bar.position.set(0, 0.045, 0.032)
      hand.add(bar)
      clock.add(hand)

      const hour = new THREE.Mesh(new THREE.BoxGeometry(0.072, 0.014, 0.012), frame)
      hour.position.set(-0.028, 0, 0.03)
      clock.add(hour)

      clock.position.set(0.9, 0.68, 0.48)
      group.add(clock)
      break
    }
    case 'sound-waves': {
      const wavesGroup = new THREE.Group()
      wavesGroup.name = 'soundWaves'
      const waveMat = new THREE.MeshBasicMaterial({
        color: 0x22d3ee,
        transparent: true,
        opacity: 0.85,
        side: THREE.DoubleSide,
      })
      for (const side of [-1, 1]) {
        for (let i = 0; i < 3; i++) {
          const rIn = 0.12 + i * 0.085
          const rOut = rIn + 0.026
          const arc = new THREE.Shape()
          const aStart = side > 0 ? Math.PI * 0.72 : -Math.PI * 0.28
          const aEnd = side > 0 ? Math.PI * 1.28 : Math.PI * 0.28
          arc.absarc(0, 0, rOut, aStart, aEnd, false)
          arc.absarc(0, 0, rIn, aEnd, aStart, true)
          arc.closePath()
          const mesh = extrudeShape(arc, 0.018, waveMat.clone())
          mesh.position.set(side * 0.96, 0.06, 0.76)
          mesh.rotation.y = -side * 0.14
          mesh.userData = { side, idx: i }
          wavesGroup.add(mesh)
        }
      }
      group.add(wavesGroup)
      break
    }
    case 'empty-box': {
      const emptyGroup = new THREE.Group()
      emptyGroup.name = 'emptySearchBadge'
      const ringMat = new THREE.MeshBasicMaterial({
        color: 0x7dd3fc,
        transparent: true,
        opacity: 0.75,
        side: THREE.DoubleSide,
      })
      const ringShape = new THREE.Shape().absarc(0, 0, 0.28, 0, Math.PI * 2, false)
      ringShape.holes.push(new THREE.Path().absarc(0, 0, 0.22, 0, Math.PI * 2, true))
      const ring = extrudeShape(ringShape, 0.026, ringMat)

      const slash = new THREE.Mesh(new THREE.BoxGeometry(0.54, 0.044, 0.03), ringMat)
      slash.rotation.z = Math.PI / 4

      emptyGroup.add(ring, slash)
      emptyGroup.position.set(0.68, 0.68, 0.56)
      group.add(emptyGroup)
      break
    }
    case 'sparks':
    case 'queue': {
      const spark = type === 'sparks'
      const shape = new THREE.Shape()
      for (let i = 0; i < 8; i++) {
        const angle = (i * Math.PI) / 4
        const radius = i % 2 ? 0.024 : 0.076
        shape[i ? 'lineTo' : 'moveTo'](Math.cos(angle) * radius, Math.sin(angle) * radius)
      }
      shape.closePath()
      for (let i = 0; i < (spark ? 2 : 3); i++) {
        const material = new THREE.MeshStandardMaterial({
          color: spark ? 0x68ddff : 0xbadfff,
          emissive: 0x249fff,
          emissiveIntensity: 0.48,
        })
        const geometry = spark
          ? new THREE.ExtrudeGeometry(shape, { depth: 0.025, bevelEnabled: false })
          : new THREE.SphereGeometry(0.045, 16, 12)
        const mesh = new THREE.Mesh(geometry, material)
        mesh.position.set(
          0.68 + i * 0.16,
          0.81 + (spark ? i * 0.15 : Math.sin((i * Math.PI) / 2) * 0.08),
          0.32,
        )
        group.add(mesh)
      }
      break
    }
    case 'bubbles': {
      const mat = plasticMat(palette.body)
      mat.roughness = 0.1
      mat.clearcoat = 0.9
      for (const [r, x, y] of [
        [0.08, 0.72, 0.72],
        [0.13, 0.88, 0.98],
        [0.22, 1.12, 1.34],
      ]) {
        const m = new THREE.Mesh(
          new THREE.SphereGeometry(r, 24, 24),
          r === 0.22
            ? new THREE.MeshStandardMaterial({
                color: 0xffd779,
                emissive: 0xffa125,
                emissiveIntensity: 0.45,
                roughness: 0.28,
              })
            : mat,
        )
        m.position.set(x, y, 0.2)
        group.add(m)
      }
      break
    }
    case 'dag': {
      const dagGroup = new THREE.Group()
      dagGroup.name = 'dagGraph'

      const panelGeo = new THREE.PlaneGeometry(0.96, 0.62)
      const panelMat = new THREE.MeshBasicMaterial({
        color: 0x4f46e5,
        transparent: true,
        opacity: 0.14,
        side: THREE.DoubleSide,
        depthWrite: false,
      })
      const panel = new THREE.Mesh(panelGeo, panelMat)
      const border = new THREE.LineSegments(
        new THREE.EdgesGeometry(panelGeo),
        new THREE.LineBasicMaterial({ color: 0xa5b4fc, transparent: true, opacity: 0.55 }),
      )
      dagGroup.add(panel, border)

      const coords = [
        [-0.32, 0.0],
        [-0.08, 0.16],
        [-0.08, -0.16],
        [0.16, 0.0],
        [0.36, 0.0],
      ]
      const edges = [
        [0, 1],
        [0, 2],
        [1, 3],
        [2, 3],
        [3, 4],
      ]

      const edgesGrp = new THREE.Group()
      edgesGrp.name = 'dagEdges'
      for (const [a, b] of edges) {
        const p1 = new THREE.Vector3(coords[a][0], coords[a][1], 0.012)
        const p2 = new THREE.Vector3(coords[b][0], coords[b][1], 0.012)
        const len = p1.distanceTo(p2)
        const bar = new THREE.Mesh(
          new THREE.BoxGeometry(len, 0.018, 0.012),
          new THREE.MeshBasicMaterial({ color: 0x818cf8, transparent: true, opacity: 0.65 }),
        )
        bar.position.copy(p1).add(p2).multiplyScalar(0.5)
        bar.rotation.z = Math.atan2(p2.y - p1.y, p2.x - p1.x)
        bar.userData = { from: a, to: b }
        edgesGrp.add(bar)
      }

      const nodesGrp = new THREE.Group()
      nodesGrp.name = 'dagNodes'
      coords.forEach(([x, y], idx) => {
        const mat = plasticMat(0xc7d2fe)
        mat.emissive = new THREE.Color(0x6366f1)
        mat.emissiveIntensity = 0.25
        const node = new THREE.Mesh(new THREE.SphereGeometry(0.056, 24, 24), mat)
        node.position.set(x, y, 0.025)
        node.userData = { idx, basePos: [x, y] }
        nodesGrp.add(node)
      })

      dagGroup.add(edgesGrp, nodesGrp)
      dagGroup.position.set(0.46, 0.36, 1.08)
      dagGroup.rotation.y = -0.26
      group.add(dagGroup)
      break
    }
    case 'test-tubes': {
      const tubesGroup = new THREE.Group()
      tubesGroup.name = 'testTubes'
      const glassMat = new THREE.MeshPhysicalMaterial({
        color: 0xffffff,
        transparent: true,
        opacity: 0.34,
        roughness: 0.12,
        clearcoat: 0.9,
        depthWrite: false,
      })
      const rimMat = new THREE.MeshStandardMaterial({
        color: 0xcbd5e1,
        roughness: 0.3,
        metalness: 0.6,
      })

      for (let i = 0; i < 3; i++) {
        const tube = new THREE.Group()
        tube.position.set((i - 1) * 0.34, 0, 0)

        const body = new THREE.Mesh(
          new THREE.CylinderGeometry(0.072, 0.072, 0.44, 24, 1, true),
          glassMat,
        )
        const bottomCap = new THREE.Mesh(
          new THREE.SphereGeometry(0.072, 24, 16, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2),
          glassMat,
        )
        bottomCap.position.y = -0.22
        const rim = new THREE.Mesh(new THREE.TorusGeometry(0.076, 0.014, 12, 24), rimMat)
        rim.rotation.x = Math.PI / 2
        rim.position.y = 0.22

        const liquidMat = new THREE.MeshStandardMaterial({
          color: 0xf59e0b,
          emissive: 0xd97706,
          emissiveIntensity: 0.45,
          roughness: 0.2,
        })
        const liquid = new THREE.Mesh(new THREE.CylinderGeometry(0.058, 0.058, 0.28, 20), liquidMat)
        liquid.name = 'liquid'
        liquid.position.y = -0.07

        const liquidBottom = new THREE.Mesh(
          new THREE.SphereGeometry(0.058, 20, 12, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2),
          liquidMat,
        )
        liquidBottom.name = 'liquidBottom'
        liquidBottom.position.y = -0.21

        const bubbles = new THREE.Group()
        bubbles.name = 'tubeBubbles'
        for (let b = 0; b < 3; b++) {
          const bub = new THREE.Mesh(
            new THREE.SphereGeometry(0.016 + b * 0.004, 12, 12),
            new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85 }),
          )
          bub.userData = { phase: b * 0.33 + i * 0.2, xOff: (b - 1) * 0.022 }
          bubbles.add(bub)
        }

        tube.add(body, bottomCap, rim, liquid, liquidBottom, bubbles)
        tubesGroup.add(tube)
      }

      tubesGroup.position.set(0, -0.34, 0.92)
      group.add(tubesGroup)
      break
    }
    case 'wrench': {
      const wrenchGrip = new THREE.Group()
      wrenchGrip.name = 'wrenchGrip'

      const forgedMat = new THREE.MeshStandardMaterial({
        color: 0xb0b7c3,
        metalness: 0.82,
        roughness: 0.28,
      })
      const goldNutMat = new THREE.MeshStandardMaterial({
        color: 0xfbbf24,
        emissive: 0xd97706,
        emissiveIntensity: 0.25,
        metalness: 0.85,
        roughness: 0.22,
      })

      const nutShape = new THREE.Shape()
      for (let i = 0; i < 6; i++) {
        const a = (i * Math.PI) / 3 + Math.PI / 6
        const r = 0.084
        if (i === 0) nutShape.moveTo(Math.cos(a) * r, Math.sin(a) * r)
        else nutShape.lineTo(Math.cos(a) * r, Math.sin(a) * r)
      }
      nutShape.closePath()
      nutShape.holes.push(new THREE.Path().absarc(0, 0, 0.036, 0, Math.PI * 2, true))
      const goldNut = extrudeShape(nutShape, 0.048, goldNutMat)
      goldNut.name = 'goldNut'
      wrenchGrip.add(goldNut)

      const L = 0.52
      const jawAngle = 0.26
      const flatW = 0.073
      const rotJaw = (x, y) => {
        const c = Math.cos(jawAngle),
          s = Math.sin(jawAngle)
        return [x * c - y * s, x * s + y * c]
      }

      const [tipLx, tipLy] = rotJaw(-flatW, 0.145)
      const [inLx, inLy] = rotJaw(-flatW, 0.005)
      const [inBx, inBy] = rotJaw(0, -0.068)
      const [inRx, inRy] = rotJaw(flatW, 0.005)
      const [tipRx, tipRy] = rotJaw(flatW, 0.135)

      const [outRx, outRy] = rotJaw(0.148, 0.095)
      const [bulgeRx, bulgeRy] = rotJaw(0.195, -0.02)
      const [neckRx, neckRy] = rotJaw(0.095, -0.145)

      const [outLx, outLy] = rotJaw(-0.148, 0.105)
      const [bulgeLx, bulgeLy] = rotJaw(-0.195, -0.01)
      const [neckLx, neckLy] = rotJaw(-0.095, -0.145)

      const wrenchShape = new THREE.Shape()
      wrenchShape.moveTo(tipLx, tipLy)
      wrenchShape.lineTo(inLx, inLy)
      wrenchShape.quadraticCurveTo(inBx, inBy, inRx, inRy)
      wrenchShape.lineTo(tipRx, tipRy)

      wrenchShape.lineTo(outRx, outRy)
      wrenchShape.quadraticCurveTo(bulgeRx, bulgeRy, neckRx, neckRy)
      wrenchShape.quadraticCurveTo(0.052, -0.175, 0.042, -0.21)

      wrenchShape.lineTo(0.042, -L + 0.115)
      const ringR = 0.098
      const aJoin = Math.atan2(0.115, 0.042)
      wrenchShape.absarc(0, -L, ringR, aJoin, Math.PI - aJoin, true)

      wrenchShape.lineTo(-0.042, -0.21)
      wrenchShape.quadraticCurveTo(-0.052, -0.175, neckLx, neckLy)
      wrenchShape.quadraticCurveTo(bulgeLx, bulgeLy, outLx, outLy)
      wrenchShape.lineTo(tipLx, tipLy)
      wrenchShape.closePath()

      wrenchShape.holes.push(new THREE.Path().absarc(0, -L, 0.052, 0, Math.PI * 2, true))

      const wrenchGeo = new THREE.ExtrudeGeometry(wrenchShape, {
        depth: 0.032,
        bevelEnabled: true,
        bevelSize: 0.005,
        bevelThickness: 0.005,
        bevelSegments: 3,
      })
      wrenchGeo.translate(0, 0, -0.016)
      const wrenchMesh = new THREE.Mesh(wrenchGeo, forgedMat)

      const tool = new THREE.Group()
      tool.name = 'wrenchTool'
      tool.add(wrenchMesh)

      const sparksGrp = new THREE.Group()
      sparksGrp.name = 'fixSparks'
      for (let i = 0; i < 6; i++) {
        const sp = new THREE.Mesh(
          new THREE.BoxGeometry(0.085, 0.022, 0.022),
          new THREE.MeshBasicMaterial({
            color: i % 2 === 0 ? 0xfef08a : 0xfb923c,
            transparent: true,
            opacity: 0.9,
          }),
        )
        sp.userData = { ang: (i / 6) * Math.PI * 2 + 0.3, phase: i * 0.17 }
        sparksGrp.add(sp)
      }

      wrenchGrip.add(tool, sparksGrp)
      group.add(wrenchGrip)
      break
    }
    case 'orbit': {
      const orbitGroup = new THREE.Group()
      orbitGroup.name = 'memoryOrbit'

      const ringShape = new THREE.Shape().absarc(0, 0, 1.12, 0, Math.PI * 2, false)
      ringShape.holes.push(new THREE.Path().absarc(0, 0, 1.07, 0, Math.PI * 2, true))
      const haloRing = extrudeShape(
        ringShape,
        0.015,
        new THREE.MeshBasicMaterial({
          color: 0xc084fc,
          transparent: true,
          opacity: 0.42,
          side: THREE.DoubleSide,
        }),
      )
      haloRing.rotation.x = Math.PI / 2 - 0.24
      haloRing.position.y = 0.26
      orbitGroup.add(haloRing)

      const orbs = new THREE.Group()
      orbs.name = 'memoryOrbs'
      const fragColors = [0xc084fc, 0xe879f9, 0x38bdf8, 0xa855f7, 0xd8b4fe, 0x818cf8]
      for (let i = 0; i < 6; i++) {
        const mat = plasticMat(fragColors[i])
        mat.emissive = new THREE.Color(fragColors[i]).multiplyScalar(0.35)
        mat.transparent = true
        const orb = new THREE.Mesh(new THREE.OctahedronGeometry(0.115, 0), mat)
        orb.userData = {
          baseAngle: (i / 6) * Math.PI * 2,
          phase: i / 6,
        }
        orbs.add(orb)
      }
      orbitGroup.add(orbs)
      group.add(orbitGroup)
      break
    }
    case 'git-merge': {
      const mergeGroup = new THREE.Group()
      mergeGroup.name = 'gitMerge'

      const leftMat = plasticMat(0x38bdf8)
      leftMat.emissive = new THREE.Color(0x0284c7).multiplyScalar(0.35)
      const rightMat = plasticMat(0xc084fc)
      rightMat.emissive = new THREE.Color(0x9333ea).multiplyScalar(0.35)
      const mergedMat = plasticMat(0x10b981)
      mergedMat.emissive = new THREE.Color(0x059669).multiplyScalar(0.5)

      const leftNode = new THREE.Mesh(new THREE.SphereGeometry(0.088, 24, 24), leftMat)
      leftNode.name = 'mergeLeft'
      const rightNode = new THREE.Mesh(new THREE.SphereGeometry(0.088, 24, 24), rightMat)
      rightNode.name = 'mergeRight'
      const centerNode = new THREE.Mesh(new THREE.SphereGeometry(0.125, 28, 28), mergedMat)
      centerNode.name = 'mergeCenter'

      const leftTrail = new THREE.Mesh(new THREE.CylinderGeometry(0.022, 0.022, 0.42, 16), leftMat)
      leftTrail.name = 'leftTrail'
      const rightTrail = new THREE.Mesh(
        new THREE.CylinderGeometry(0.022, 0.022, 0.42, 16),
        rightMat,
      )
      rightTrail.name = 'rightTrail'

      const flashShape = new THREE.Shape().absarc(0, 0, 0.34, 0, Math.PI * 2, false)
      flashShape.holes.push(new THREE.Path().absarc(0, 0, 0.28, 0, Math.PI * 2, true))
      const flashRing = extrudeShape(
        flashShape,
        0.018,
        new THREE.MeshBasicMaterial({
          color: 0x34d399,
          transparent: true,
          opacity: 0.85,
          side: THREE.DoubleSide,
        }),
      )
      flashRing.name = 'mergeFlash'

      mergeGroup.add(leftTrail, rightTrail, leftNode, rightNode, centerNode, flashRing)
      mergeGroup.position.set(0, -0.14, 0.88)
      group.add(mergeGroup)
      break
    }
    case 'magnifier': {
      const mag = new THREE.Group()
      const rimPts = [
        [0.235, -0.016],
        [0.235, 0.016],
        [0.239, 0.02],
        [0.281, 0.02],
        [0.285, 0.016],
        [0.285, -0.016],
        [0.281, -0.02],
        [0.239, -0.02],
        [0.235, -0.016],
      ]
      const rim = new THREE.Mesh(
        new THREE.LatheGeometry(
          rimPts.map(([x, y]) => new THREE.Vector2(x, y)),
          96,
        ).rotateX(Math.PI / 2),
        new THREE.MeshStandardMaterial({
          color: 0xc4a264,
          roughness: 0.24,
          metalness: 0.85,
          side: THREE.DoubleSide,
        }),
      )
      const lensShaderMat = new THREE.ShaderMaterial({
        uniforms: {
          tScene: { value: null },
          uResolution: { value: new THREE.Vector2(1, 1) },
          uLensCenter: { value: new THREE.Vector2(0.5, 0.5) },
          uZoom: { value: 1.75 },
        },
        vertexShader: `
          varying vec2 vUv;
          void main() {
            vUv = uv;
            gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
          }
        `,
        fragmentShader: `
          uniform sampler2D tScene;
          uniform vec2 uResolution;
          uniform vec2 uLensCenter;
          uniform float uZoom;
          varying vec2 vUv;

          void main() {
            vec2 centered = (vUv - 0.5) * 2.0;
            float r2 = dot(centered, centered);
            if (r2 > 1.0) discard;

            vec2 screenUv = gl_FragCoord.xy / uResolution;
            float barrel = 1.0 + 0.08 * r2;
            vec2 refractedUv = uLensCenter + (screenUv - uLensCenter) * (barrel / uZoom);
            refractedUv = clamp(refractedUv, vec2(0.001), vec2(0.999));

            vec4 texel = texture2D(tScene, refractedUv);
            vec3 finalColor = texel.rgb;

            float tintAlpha = 0.075 + 0.055 * smoothstep(0.65, 1.0, r2);
            vec3 tint = vec3(0.48, 0.80, 0.88);
            float alpha = texel.a + tintAlpha * (1.0 - texel.a);
            vec3 tinted = mix(finalColor, tint, tintAlpha);
            vec3 premixed = finalColor * texel.a * (1.0 - tintAlpha) + tint * tintAlpha;
            gl_FragColor = vec4(texel.a > 0.99 ? tinted : premixed / max(alpha, 0.001), alpha);
            #include <colorspace_fragment>
          }
        `,
        transparent: true,
        depthWrite: false,
        side: THREE.DoubleSide,
      })
      const glass = new THREE.Mesh(new THREE.CircleGeometry(0.238, 64), lensShaderMat)
      glass.name = 'magGlass'
      const handle = new THREE.Mesh(
        new THREE.CylinderGeometry(0.04, 0.04, 0.35, 16),
        new THREE.MeshStandardMaterial({ color: 0x633426, roughness: 0.42 }),
      )
      const dir = new THREE.Vector3(1, -1, 0).normalize()
      handle.position.copy(dir).multiplyScalar(0.455)
      handle.position.z = -0.025
      handle.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir)
      mag.add(rim, glass, handle)
      mag.rotation.z = -Math.PI / 2
      const offset = handle.position
        .clone()
        .applyAxisAngle(new THREE.Vector3(0, 0, 1), mag.rotation.z)
      mag.position.copy(offset).negate()

      const grip = new THREE.Group()
      grip.name = 'grip'
      grip.position.copy(GRIP_REST)
      grip.add(mag)
      group.add(grip)
      break
    }
    case 'speed': {
      const mat = new THREE.MeshBasicMaterial({ color: new THREE.Color(palette.body) })
      for (let i = 0; i < 3; i++) {
        const bar = new THREE.Mesh(new THREE.BoxGeometry(0.65 - i * 0.1, 0.05, 0.04), mat)
        bar.position.set(-1.35 - i * 0.1, 0.1 - i * 0.14, 0.05)
        group.add(bar)
      }
      break
    }
    case 'offline': {
      const mat = plasticMat(0x64748b)
      const redMat = new THREE.MeshBasicMaterial({
        color: 0xf87171,
        transparent: true,
        opacity: 0.9,
        side: THREE.DoubleSide,
      })
      const badge = new THREE.Group()
      badge.name = 'offlineBadge'

      const dot = extrudeShape(
        new THREE.Shape().absarc(0, -0.14, 0.042, 0, Math.PI * 2, false),
        0.045,
        mat,
      )
      badge.add(dot)

      const arcsGroup = new THREE.Group()
      arcsGroup.name = 'wifiArcs'
      for (let i = 0; i < 3; i++) {
        const rIn = 0.095 + i * 0.075
        const rOut = rIn + 0.042
        const arcShape = new THREE.Shape()
        arcShape.absarc(0, -0.14, rOut, Math.PI * 0.25, Math.PI * 0.75, false)
        arcShape.absarc(0, -0.14, rIn, Math.PI * 0.75, Math.PI * 0.25, true)
        arcShape.closePath()
        const arcMesh = extrudeShape(arcShape, 0.04, mat.clone())
        arcsGroup.add(arcMesh)
      }
      badge.add(arcsGroup)

      const slash = new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.048, 0.055), redMat)
      slash.name = 'offlineSlash'
      slash.position.set(0, 0.02, 0.01)
      slash.rotation.z = -Math.PI / 4
      badge.add(slash)

      badge.position.copy(BADGE_POS)
      group.add(badge)
      break
    }
    case 'zzz': {
      for (let i = 0; i < 3; i++) {
        const mat = plasticMat(0x64748b)
        mat.transparent = true
        mat.depthWrite = false
        const zGroup = new THREE.Group()
        for (const y of [-0.065, 0.065]) {
          const bar = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.035, 0.035), mat)
          bar.position.y = y
          zGroup.add(bar)
        }
        const diag = new THREE.Mesh(new THREE.BoxGeometry(0.18, 0.035, 0.035), mat)
        diag.rotation.z = Math.PI / 4
        zGroup.add(diag)
        const extra = i === 2 ? 0.08 : 0
        zGroup.position.set(0.63 + i * 0.2 + extra, 0.85 + i * 0.2 + extra, 0.29)
        zGroup.userData.scale = 0.9 + i * 0.26
        group.add(zGroup)
      }
      break
    }
    case 'pause': {
      const mat = plasticMat(0x64748b)
      const ringMat = new THREE.MeshBasicMaterial({
        color: 0x94a3b8,
        transparent: true,
        opacity: 0.7,
        side: THREE.DoubleSide,
      })
      const badge = new THREE.Group()
      badge.name = 'pauseBadge'

      for (const x of [-0.058, 0.058]) {
        const bar = new THREE.Mesh(new THREE.BoxGeometry(0.064, 0.22, 0.06), mat)
        bar.position.x = x
        badge.add(bar)
      }

      const spinner = new THREE.Group()
      spinner.name = 'pauseSpinner'
      const segCount = 6
      for (let i = 0; i < segCount; i++) {
        const a0 = (i / segCount) * Math.PI * 2 + 0.08
        const a1 = ((i + 0.65) / segCount) * Math.PI * 2
        const segShape = new THREE.Shape()
        segShape.absarc(0, 0, 0.24, a0, a1, false)
        segShape.absarc(0, 0, 0.205, a1, a0, true)
        segShape.closePath()
        spinner.add(extrudeShape(segShape, 0.025, ringMat))
      }
      badge.add(spinner)

      badge.position.copy(BADGE_POS)
      group.add(badge)
      break
    }
    case 'alert': {
      const mat = plasticMat(palette.body)
      const curve = new THREE.CatmullRomCurve3(
        [
          [-0.24, 0.23],
          [-0.18, 0.41],
          [0.03, 0.47],
          [0.22, 0.35],
          [0.23, 0.17],
          [0.08, 0.03],
          [0, -0.12],
        ].map(([x, y]) => new THREE.Vector3(x, y, 0)),
      )
      const r = 0.075
      const samples = Array.from({ length: 49 }, (_, k) => {
        const pt = curve.getPoint(k / 48)
        const tan = curve.getTangent(k / 48)
        return { pt, angle: Math.atan2(tan.y, tan.x) + Math.PI / 2 }
      })
      const off = ({ pt, angle }, side) => [
        pt.x + side * r * Math.cos(angle),
        pt.y + side * r * Math.sin(angle),
      ]
      const qShape = new THREE.Shape()
      qShape.moveTo(...off(samples[0], 1))
      for (const s of samples.slice(1)) qShape.lineTo(...off(s, 1))
      const last = samples[samples.length - 1]
      qShape.absarc(last.pt.x, last.pt.y, r, last.angle, last.angle - Math.PI, true)
      for (const s of samples.slice(0, -1).reverse()) qShape.lineTo(...off(s, -1))
      const first = samples[0]
      qShape.absarc(
        first.pt.x,
        first.pt.y,
        r,
        first.angle - Math.PI,
        first.angle - 2 * Math.PI,
        true,
      )
      qShape.closePath()

      const badge = new THREE.Group()
      badge.add(
        extrudeShape(qShape, 0.065, mat),
        extrudeShape(new THREE.Shape().absarc(0, -0.34, 0.09, 0, Math.PI * 2, false), 0.065, mat),
      )

      const ringShape = new THREE.Shape().absarc(0, 0.04, 0.48, 0, Math.PI * 2, false)
      ringShape.holes.push(new THREE.Path().absarc(0, 0.04, 0.42, 0, Math.PI * 2, true))
      const pulseRing = extrudeShape(
        ringShape,
        0.02,
        new THREE.MeshBasicMaterial({
          color: new THREE.Color(palette.body),
          transparent: true,
          opacity: 0.75,
          side: THREE.DoubleSide,
        }),
      )
      pulseRing.name = 'alertPulseRing'
      badge.add(pulseRing)

      badge.position.copy(BADGE_POS)
      group.add(badge)
      break
    }
    case 'stress': {
      const mat = new THREE.MeshBasicMaterial({ color: 0xff5252 })
      for (const s of [-1, 1]) {
        for (let i = 0; i < 2; i++) {
          const line = new THREE.Mesh(new THREE.BoxGeometry(0.22, 0.048, 0.04), mat)
          line.position.set(s * (0.84 + i * 0.16), 0.75 - i * 0.24, 0.26)
          line.rotation.z = s * (0.85 - i * 0.5)
          line.userData.baseX = line.position.x
          line.userData.baseY = line.position.y
          line.userData.side = s
          group.add(line)
        }
      }

      const warnGroup = new THREE.Group()
      warnGroup.name = 'warnSign'
      const triShape = new THREE.Shape()
      triShape.moveTo(0, 0.24)
      triShape.lineTo(-0.22, -0.16)
      triShape.lineTo(0.22, -0.16)
      triShape.closePath()
      const hole = new THREE.Path()
      hole.moveTo(0, 0.15)
      hole.lineTo(-0.13, -0.1)
      hole.lineTo(0.13, -0.1)
      hole.closePath()
      triShape.holes.push(hole)

      const warnMat = plasticMat(0xef4444)
      warnMat.emissive = new THREE.Color(0xdc2626).multiplyScalar(0.35)
      const bangMat = new THREE.MeshBasicMaterial({
        color: 0xc81e1e,
        transparent: false,
        opacity: 1,
        toneMapped: false,
      })
      const bangBar = new THREE.Mesh(new THREE.BoxGeometry(0.042, 0.11, 0.05), bangMat)
      bangBar.position.y = 0.02
      const bangDot = new THREE.Mesh(new THREE.SphereGeometry(0.028, 16, 16), bangMat)
      bangDot.position.y = -0.065

      warnGroup.add(extrudeShape(triShape, 0.045, warnMat), bangBar, bangDot)
      warnGroup.position.set(0, 1.05, 0.18)
      group.add(warnGroup)
      break
    }
    case 'cancel-x': {
      const badge = new THREE.Group()
      badge.name = 'cancelBadge'
      const xMat = plasticMat(0x94a3b8)
      const bar1 = new THREE.Mesh(new THREE.BoxGeometry(0.32, 0.068, 0.055), xMat)
      bar1.rotation.z = Math.PI / 4
      const bar2 = new THREE.Mesh(new THREE.BoxGeometry(0.32, 0.068, 0.055), xMat)
      bar2.rotation.z = -Math.PI / 4

      const ringShape = new THREE.Shape().absarc(0, 0, 0.24, 0, Math.PI * 2, false)
      ringShape.holes.push(new THREE.Path().absarc(0, 0, 0.2, 0, Math.PI * 2, true))
      const ringMesh = extrudeShape(
        ringShape,
        0.03,
        new THREE.MeshBasicMaterial({
          color: 0xcbd5e1,
          transparent: true,
          opacity: 0.7,
          side: THREE.DoubleSide,
        }),
      )
      badge.add(bar1, bar2, ringMesh)
      badge.position.copy(BADGE_POS)
      group.add(badge)
      break
    }
    case 'confetti': {
      let seed = 7
      const rand = () => {
        seed = (seed + 0x6d2b79f5) | 0
        let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296
      }
      for (let i = 0; i < 14; i++) {
        const piece = new THREE.Mesh(
          new THREE.PlaneGeometry(0.08, 0.08),
          new THREE.MeshBasicMaterial({
            color: new THREE.Color(palette.confetti[i % palette.confetti.length]),
            side: THREE.DoubleSide,
            transparent: true,
            depthWrite: false,
          }),
        )
        piece.userData.target = new THREE.Vector3(
          (rand() - 0.5) * 2.2,
          0.6 + rand(),
          (rand() - 0.5) * 0.6 + 0.2,
        )
        group.add(piece)
      }
      break
    }
    case 'keyboard': {
      const kbGroup = new THREE.Group()
      kbGroup.name = 'holoKeyboard'
      const chassis = new THREE.MeshStandardMaterial({ color: 0x17181b, roughness: 0.62 })
      const keyMaterials = {
        W: new THREE.MeshStandardMaterial({ color: 0xf4f5f7, roughness: 0.57 }),
        N: new THREE.MeshStandardMaterial({ color: 0x17263b, roughness: 0.56 }),
        E: new THREE.MeshStandardMaterial({ color: 0x00665b, roughness: 0.53 }),
      }
      const keyboardTilt = 0.12
      const chassisGeometry = new THREE.BoxGeometry(1.22, 0.075, 0.46)
      const chassisVertices = chassisGeometry.attributes.position
      for (let i = 0; i < chassisVertices.count; i++) {
        const y = chassisVertices.getY(i)
        if (y < 0) chassisVertices.setY(i, y + chassisVertices.getZ(i) * Math.tan(keyboardTilt))
      }
      chassisGeometry.computeVertexNormals()
      const base = new THREE.Mesh(chassisGeometry, chassis)
      base.position.y = -0.0345
      const knob = new THREE.Mesh(new THREE.CylinderGeometry(0.031, 0.032, 0.034, 24), chassis)
      knob.position.set(0.534, 0.024, -0.174)
      kbGroup.add(base, knob)

      const keysGroup = new THREE.Group()
      keysGroup.name = 'keys'
      const pitch = 0.073
      const rowPitch = 0.0696
      const addKey = (kind, row, column, units = 1) => {
        const key = new THREE.Mesh(
          new THREE.BoxGeometry(units * pitch - 0.008, 0.028, 0.057),
          keyMaterials[kind],
        )
        key.position.set(
          -0.541 + (column + (units - 1) / 2) * pitch,
          0.024,
          -0.174 + row * rowPitch,
        )
        keysGroup.add(key)
      }
      ;[...'EWWWWNNNNWWWWN'].forEach((kind, column) => addKey(kind, 0, column))
      const rows = [
        [
          ['W', 13],
          ['N', 1, 2],
        ],
        [
          ['N', 1, 1.5],
          ['W', 12],
        ],
        [
          ['N', 1, 1.75],
          ['W', 12],
        ],
        [
          ['N', 1, 1.25],
          ['W', 11],
          ['N', 1, 1.75],
        ],
        [
          ['N', 3, 1.25],
          ['W', 1, 6.25],
          ['N', 3],
        ],
      ]
      rows.forEach((sections, index) => {
        let column = 0
        for (const [kind, count, units = 1] of sections) {
          for (let i = 0; i < count; i++, column += units) addKey(kind, index + 1, column, units)
        }
      })
      for (const row of [1, 2, 3]) addKey('N', row, 15)
      addKey('W', 4, 14)
      for (const column of [13, 14, 15]) addKey('W', 5, column)

      const enterShape = new THREE.Shape()
      const enterOutline = [
        [13.55, 2],
        [15, 2],
        [15, 4],
        [13.8, 4],
        [13.8, 3],
        [13.55, 3],
      ]
      enterOutline.forEach(([column, row], i) => {
        enterShape[i ? 'lineTo' : 'moveTo'](
          -0.541 + (column - 0.5) * pitch,
          -0.174 + (row - 0.5) * rowPitch,
        )
      })
      enterShape.closePath()
      const enterGeometry = new THREE.ExtrudeGeometry(enterShape, {
        depth: 0.028,
        bevelEnabled: false,
      })
      enterGeometry.rotateX(Math.PI / 2)
      enterGeometry.translate(0, 0.014, 0)
      const enter = new THREE.Mesh(enterGeometry, keyMaterials.N)
      enter.position.y = 0.024
      keysGroup.add(enter)
      kbGroup.add(keysGroup)
      kbGroup.position.set(0, -0.68, 0.78)
      kbGroup.rotation.x = keyboardTilt
      kbGroup.rotation.order = 'YXZ'
      kbGroup.rotation.y = Math.PI
      group.add(kbGroup)
      break
    }
    case 'thruster': {
      for (let i = 0; i < 3; i++) {
        const rOuter = 0.52 - i * 0.11
        const ringShape = new THREE.Shape().absarc(0, 0, rOuter, 0, Math.PI * 2, false)
        ringShape.holes.push(new THREE.Path().absarc(0, 0, rOuter - 0.065, 0, Math.PI * 2, true))
        const mat = new THREE.MeshBasicMaterial({
          color: i === 0 ? 0x38bdf8 : i === 1 ? 0x7dd3fc : 0xe0f2fe,
          side: THREE.DoubleSide,
          transparent: true,
          opacity: 0.85,
        })
        const mesh = extrudeShape(ringShape, 0.03, mat)
        mesh.rotation.x = Math.PI / 2
        mesh.position.set(0, -0.78 - i * 0.18, 0)
        group.add(mesh)
      }
      break
    }
    case 'sparkles': {
      const starShape = new THREE.Shape()
      const pts = 4,
        outerR = 0.14,
        innerR = 0.05
      for (let i = 0; i < pts * 2; i++) {
        const r = i % 2 === 0 ? outerR : innerR
        const a = (i * Math.PI) / pts
        if (i === 0) starShape.moveTo(Math.cos(a) * r, Math.sin(a) * r)
        else starShape.lineTo(Math.cos(a) * r, Math.sin(a) * r)
      }
      starShape.closePath()
      const starColors = [0xfbbf24, 0xf472b6, 0x60a5fa, 0x34d399, 0xc084fc, 0xfbbf24]
      for (let i = 0; i < 6; i++) {
        const mat = plasticMat(starColors[i])
        mat.emissive = new THREE.Color(starColors[i]).multiplyScalar(0.25)
        const star = extrudeShape(starShape, 0.04, mat)
        star.userData.angle = (i / 6) * Math.PI * 2
        star.userData.yBase = 0.15 + (i % 3) * 0.35
        group.add(star)
      }
      break
    }
    case 'scanner': {
      const scanGroup = new THREE.Group()
      scanGroup.name = 'scanBeam'
      const coneGeo = new THREE.ConeGeometry(0.54, 0.88, 48, 1, true)
      coneGeo.translate(0, -0.44, 0)
      coneGeo.rotateX(-Math.PI / 2)
      const coneMat = new THREE.MeshBasicMaterial({
        color: 0x10b981,
        transparent: true,
        opacity: 0.16,
        side: THREE.DoubleSide,
        depthWrite: false,
      })
      const cone = new THREE.Mesh(coneGeo, coneMat)

      const laserBar = new THREE.Mesh(
        new THREE.BoxGeometry(1, 0.024, 0.024),
        new THREE.MeshBasicMaterial({ color: 0xff4757 }),
      )
      laserBar.name = 'laserLine'
      laserBar.position.set(0, 0, 0.89)

      const targetRingShape = new THREE.Shape().absarc(0, 0, 0.54, 0, Math.PI * 2, false)
      targetRingShape.holes.push(new THREE.Path().absarc(0, 0, 0.5, 0, Math.PI * 2, true))
      const targetRing = extrudeShape(
        targetRingShape,
        0.015,
        new THREE.MeshBasicMaterial({ color: 0x34d399, transparent: true, opacity: 0.65 }),
      )
      targetRing.position.z = 0.88

      scanGroup.add(cone, laserBar, targetRing)
      scanGroup.position.set(0, -0.04, 0.52)
      group.add(scanGroup)
      break
    }
    case 'sweat': {
      const dropMat = plasticMat(0x38bdf8)
      dropMat.roughness = 0.1
      dropMat.clearcoat = 0.95
      dropMat.emissive = new THREE.Color(0x0284c7).multiplyScalar(0.2)
      const dropPts = [
        [0.001, -0.12],
        [0.075, -0.09],
        [0.095, -0.03],
        [0.08, 0.04],
        [0.04, 0.12],
        [0.001, 0.19],
      ].map(([x, y]) => new THREE.Vector2(x, y))
      const dropGeo = new THREE.LatheGeometry(dropPts, 32)

      const configs = [
        { x: -0.52, y: 0.78, z: 0.52, phase: 0, s: 1.15, tilt: 0.22 },
        { x: 0.54, y: 0.72, z: 0.52, phase: 0.38, s: 1.05, tilt: -0.22 },
        { x: -0.32, y: 0.92, z: 0.42, phase: 0.72, s: 0.95, tilt: 0.12 },
      ]
      for (const cfg of configs) {
        const drop = new THREE.Mesh(dropGeo, dropMat)
        drop.position.set(cfg.x, cfg.y, cfg.z)
        drop.rotation.z = cfg.tilt
        drop.userData = { ...cfg }
        group.add(drop)
      }
      break
    }
  }
  return group
}
