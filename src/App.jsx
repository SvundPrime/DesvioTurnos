import { useEffect, useMemo, useState } from "react";
import { addDoc, orderBy, limit } from "firebase/firestore";
import { onAuthStateChanged, signInWithEmailAndPassword, signOut } from "firebase/auth";
import {
  collection,
  doc,
  getDocs,
  onSnapshot,
  query,
  runTransaction,
  serverTimestamp,
  setDoc,
  writeBatch,
  where,
} from "firebase/firestore";
import { auth, db } from "./firebase";
import SelectControl from "./SelectControl";



const NONE = "__NONE__";
const DAYS_LV = ["MON", "TUE", "WED", "THU", "FRI"];
const DAYS_DJ_NOCHE = ["SUN", "MON", "TUE", "WED", "THU"];

const MODE = {
  NORMAL: "NORMAL",
  OFICINA: "OFICINA",
  FESTIVO: "FESTIVO",
};

const SHIFT_META = {
  morning: { label: "Mañana (07:00–15:00 · L–V)" },
  intershift: { label: "Entreturno (L–J 08:30–17:30 · V 08:00–15:00)" },
  afternoon: { label: "Tarde (15:00–23:00 · L–V)" },
  night: { label: "Noche (23:00–07:00 · D–J)" },
};

const DEVICES = {
  REDIRIS: "rediris",
  TELEFONICA: "telefonica",
};

const DEVICE_LIST = [DEVICES.REDIRIS, DEVICES.TELEFONICA];
const LOCK_TTL_MS = 120_000;

const THEME = {
  bg: "#07131A",
  bg2: "#0A1B24",
  card: "#0D2430",
  card2: "#0B202B",
  border: "rgba(255,255,255,0.10)",
  text: "rgba(255,255,255,0.92)",
  text2: "rgba(255,255,255,0.74)",
  muted: "rgba(255,255,255,0.60)",
  brand: "#19B3B8",
  brand2: "#2DD4C7",
  danger: "#FF5A6A",
  warn: "#FFB020",
  ok: "#2EE59D",
  shadow: "0 10px 30px rgba(0,0,0,0.35)",
};
const ACTION_ES = {
  APPLY_NOW: "Aplicar ahora",
};

function prettyAction(a) {
  const k = String(a || "").toUpperCase();
  return ACTION_ES[k] || a || "-";
}



const isNoneLike = (v) => {
  if (v == null) return true;
  const s = String(v).trim();
  return s === "" || s === NONE || s.toUpperCase() === "NULL";
};
const toNullableId = (v) => (isNoneLike(v) ? null : String(v).trim());
const toSelectValue = (v) => (isNoneLike(v) ? NONE : String(v).trim());

const makeRequestId = () => `${Date.now()}-${Math.random().toString(16).slice(2)}`;
function cleanCfg(cfg) {
  const mode = Object.values(MODE).includes(String(cfg?.mode || "").toUpperCase())
    ? String(cfg.mode).toUpperCase()
    : MODE.NORMAL;

  const shifts = cfg?.shifts || {};
  return {
    mode,
    n2GuardId: toSelectValue(cfg?.n2GuardId),
    shifts: {
      morning: toSelectValue(shifts.morning),
      intershift: toSelectValue(shifts.intershift),
      afternoon: toSelectValue(shifts.afternoon),
      night: toSelectValue(shifts.night),
    },
  };
}

function makeEmptyCfg() {
  return {
    mode: MODE.NORMAL,
    n2GuardId: NONE,
    shifts: { morning: NONE, intershift: NONE, afternoon: NONE, night: NONE },
  };
}

function normalizeCfg(data) {
  const mode = String(data?.mode || "").toUpperCase();
  const safeMode = Object.values(MODE).includes(mode) ? mode : null;
  const legacyOffice = !!data?.officeMode;
  const legacyHoliday = !!data?.forceHoliday;
  const legacyN1Active = (data?.n1Active ?? true) === true;

  const inferredMode =
    legacyHoliday ? MODE.FESTIVO
    : legacyOffice ? MODE.OFICINA
    : legacyN1Active ? MODE.NORMAL
    : MODE.FESTIVO;

  const finalMode = safeMode || inferredMode;
  const intershiftOld = data?.shifts?.intershift?.contactId;
  const intershiftLJ  = data?.shifts?.intershiftLJ?.contactId;
  const intershiftFri = data?.shifts?.intershiftFri?.contactId;

  const pickIntershift =
    !isNoneLike(intershiftLJ) ? intershiftLJ :
    !isNoneLike(intershiftFri) ? intershiftFri :
    !isNoneLike(intershiftOld) ? intershiftOld :
    NONE;

  const cfg = {
    mode: finalMode,
    n2GuardId: toSelectValue(data?.n2GuardId),
    shifts: {
      morning: toSelectValue(data?.shifts?.morning?.contactId),
      intershift: toSelectValue(pickIntershift),
      afternoon: toSelectValue(data?.shifts?.afternoon?.contactId),
      night: toSelectValue(data?.shifts?.night?.contactId),
    },
  };

  return cleanCfg(cfg);
}

function buildOptionsForDevice(contacts, deviceId, role, allowNone = true) {
  const base = allowNone ? [{ id: NONE, label: "— Sin asignar —" }] : [];
  const allowed = role === "N2" ? ["N2", "BOTH"] : ["N1", "BOTH"];

  const filtered = contacts
    .filter((c) => {
      const hasLabel =
        c?.labelsByDevice &&
        typeof c.labelsByDevice === "object" &&
        !!c.labelsByDevice[deviceId];

      const lvl = String(c?.level || "").toUpperCase();
      const okLevel = allowed.includes(lvl);

      return hasLabel && okLevel;
    })
    .map((c) => ({ id: c.id, label: c.displayName || c.id }));

  return base.concat(filtered);
}

function prettyStatus(s) {
  switch (String(s).toLowerCase()) {
    case "running":
      return "ejecutando";
    case "idle":
      return "en espera";
    default:
      return "sin datos";
  }
}

function badgeForStatus(status, styles) {
  const s = String(status).toLowerCase();
  if (s === "running") return { ...styles.badge, borderColor: THEME.brand, color: THEME.brand2 };
  if (s === "idle") return { ...styles.badge, borderColor: "rgba(255,255,255,0.18)", color: THEME.text2 };
  return { ...styles.badge, borderColor: "rgba(255,255,255,0.14)", color: THEME.muted };
}

function badgeForApplySource(source, styles) {
  const s = String(source || "").toUpperCase();
  if (s === "AUTO") return { ...styles.badge, borderColor: "rgba(25,179,184,0.55)", color: THEME.brand2 };
  if (s === "MANUAL") return { ...styles.badge, borderColor: "rgba(255,176,32,0.55)", color: THEME.warn };
  if (s === "FORCED") return { ...styles.badge, borderColor: "rgba(231,76,60,0.6)", color: "#ffb3a7" };
  return { ...styles.badge, borderColor: "rgba(255,255,255,0.16)", color: THEME.muted };
}

function isDeviceBusy(d) {
  if (!d) return false;
  const statusRunning = String(d.status || "").toLowerCase() === "running";
  const lock = d.applyLock;
  const now = Date.now();
  const lockActive = lock?.locked && typeof lock?.expiresAt === "number" && lock.expiresAt > now;
  return statusRunning || lockActive;
}

function badgeForLog(l, styles) {
  const t = String(l.type || "").toUpperCase();
  if (t.includes("CONFIG")) return { ...styles.badge, borderColor: "rgba(25,179,184,0.55)", color: THEME.brand2 };
  if (t.includes("COMANDO")) return { ...styles.badge, borderColor: "rgba(255,176,32,0.55)", color: THEME.warn };
  return { ...styles.badge, borderColor: "rgba(255,255,255,0.16)", color: THEME.text2 };
}



function useMediaQuery(queryStr) {
  const [matches, setMatches] = useState(() => {
    if (typeof window === "undefined") return false;
    return window.matchMedia(queryStr).matches;
  });

  useEffect(() => {
    if (typeof window === "undefined") return;
    const mq = window.matchMedia(queryStr);
    const onChange = () => setMatches(mq.matches);
    onChange();
    mq.addEventListener?.("change", onChange);
    return () => mq.removeEventListener?.("change", onChange);
  }, [queryStr]);

  return matches;
}



function makeStyles(isMobile) {
  const pagePad = isMobile ? 12 : 24;
  const shellPad = isMobile ? "0 6px" : "0 16px";
  const cardPad = isMobile ? 12 : 16;
  const br = isMobile ? 14 : 16;

  return {
    page: {
      minHeight: "100vh",
      background: `radial-gradient(1200px 700px at 20% 0%, rgba(25,179,184,0.14), transparent 60%),
                   radial-gradient(900px 600px at 90% 10%, rgba(45,212,199,0.10), transparent 55%),
                   linear-gradient(180deg, ${THEME.bg}, ${THEME.bg2})`,
      color: THEME.text,
      padding: pagePad,
      fontFamily:
        'ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, "Helvetica Neue", Arial, "Noto Sans", "Liberation Sans", sans-serif',
      ...(isMobile
        ? { display: "grid", justifyItems: "center" }
        : {}),
    },
    shell: {
      width: "100%",
      margin: "0 auto",
      padding: shellPad,
      display: "grid",
      gap: 16,
      boxSizing: "border-box",
      ...(isMobile ? { maxWidth: 420 } : {}),
    },

    header: {
      background: `linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.02))`,
      border: `1px solid ${THEME.border}`,
      borderRadius: br,
      padding: cardPad,
      boxShadow: THEME.shadow,
      display: "flex",
      justifyContent: "space-between",
      alignItems: "center",
      gap: 16,
      ...(isMobile
        ? { flexDirection: "column", alignItems: "stretch", gap: 12 }
        : {}),
    },

    gridMain: {
      display: "grid",
      gridTemplateColumns: isMobile
        ? "1fr"
        : "minmax(360px, 1fr) minmax(360px, 1fr) minmax(360px, 1fr)",
      gap: 16,
      alignItems: "start",
      width: "100%",
      boxSizing: "border-box",
    },

    sideStack: { display: "grid", gap: 16 },

    headerTitle: { fontSize: isMobile ? 16 : 18, fontWeight: 900, letterSpacing: 0.2 },
    headerSub: { fontSize: isMobile ? 12 : 13, color: THEME.text2, marginTop: 2 },

    brandMark: {
      width: isMobile ? 30 : 34,
      height: isMobile ? 30 : 34,
      borderRadius: 12,
      background: `radial-gradient(circle at 30% 20%, ${THEME.brand2}, ${THEME.brand} 45%, rgba(25,179,184,0.15) 80%)`,
      boxShadow: "0 8px 22px rgba(25,179,184,0.25)",
      flex: "0 0 auto",
    },

    card: {
      background: `linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.02))`,
      border: `1px solid ${THEME.border}`,
      borderRadius: br,
      padding: cardPad,
      boxShadow: THEME.shadow,
      width: "100%",
      boxSizing: "border-box",
    },

    cardTitle: { fontSize: isMobile ? 15 : 16, fontWeight: 900, color: THEME.text },
    cardSub: { fontSize: isMobile ? 12 : 13, color: THEME.text2, marginTop: 4 },

    divider: {
      height: 1,
      background: "rgba(255,255,255,0.10)",
      margin: isMobile ? "12px 0" : "14px 0",
    },

    formBlock: { display: "grid", gap: 10 },

    label: { fontSize: isMobile ? 12 : 13, color: THEME.text2 },

    input: {
      width: "100%",
      padding: isMobile ? "10px 10px" : "11px 12px",
      borderRadius: 12,
      border: `1px solid rgba(255,255,255,0.12)`,
      background: "rgba(255,255,255,0.04)",
      color: THEME.text,
      outline: "none",
      marginBottom: 12,
      boxSizing: "border-box",
      maxWidth: "100%",
      fontSize: isMobile ? 13 : 14,
    },

    radio: {
      width: 18,
      height: 18,
      accentColor: THEME.brand,
    },

    toggleRow: {
      display: "flex",
      justifyContent: "space-between",
      alignItems: "center",
      gap: 12,
      padding: isMobile ? "8px 0" : "10px 0",
    },
    primaryBtn: {
      width: "100%",
      padding: isMobile ? "11px 12px" : "12px 14px",
      borderRadius: 12,
      border: "1px solid rgba(25,179,184,0.35)",
      background: `linear-gradient(135deg, rgba(25,179,184,0.90), rgba(45,212,199,0.70))`,
      color: "#001014",
      fontWeight: 900,
      cursor: "pointer",
      boxShadow: "0 12px 24px rgba(25,179,184,0.18)",
      fontSize: isMobile ? 13 : 14,
    },

    ghostBtn: {
      width: "auto",
      padding: isMobile ? "11px 12px" : "10px 14px",
      borderRadius: 12,
      border: `1px solid rgba(255,255,255,0.14)`,
      background: "rgba(255,255,255,0.03)",
      color: THEME.text,
      fontWeight: 800,
      cursor: "pointer",
      fontSize: isMobile ? 13 : 14,
    },

    sectionCard: {
      background: `linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.02))`,
      border: `1px solid ${THEME.border}`,
      borderRadius: br,
      padding: cardPad,
      boxShadow: THEME.shadow,
      width: "100%",
      boxSizing: "border-box",
    },

    sectionHead: {
      display: "flex",
      justifyContent: "space-between",
      gap: 12,
      alignItems: "baseline",
      marginBottom: 12,
    },

    sectionTitle: { margin: 0, fontSize: isMobile ? 14 : 15, fontWeight: 900, color: THEME.text },
    sectionHint: { fontSize: 12, color: THEME.muted },

    deviceCard: {
      padding: isMobile ? 12 : 14,
      borderRadius: 14,
      border: `1px solid rgba(255,255,255,0.10)`,
      background: `linear-gradient(180deg, rgba(0,0,0,0.10), rgba(255,255,255,0.02))`,
    },

    kvGrid: {
      marginTop: 10,
      display: "grid",
      gridTemplateColumns: isMobile ? "1fr" : "1fr 1fr",
      gap: 10,
    },

    badge: {
      fontSize: 12,
      fontWeight: 900,
      borderRadius: 999,
      padding: "6px 10px",
      border: "1px solid rgba(255,255,255,0.14)",
      background: "rgba(255,255,255,0.03)",
      color: THEME.text2,
      textTransform: "uppercase",
      letterSpacing: 0.4,
      whiteSpace: "nowrap",
    },

    logsCard: {
      height: isMobile ? "auto" : 243,
      display: "flex",
      flexDirection: "column",
    },

    logsWrap: {
      borderTop: "1px solid rgba(255,255,255,0.08)",
      marginTop: 6,
      overflowY: "auto",
      paddingRight: 6,
      flex: 1,
      maxHeight: isMobile ? 280 : "unset",
    },

    logRow: {
      display: "flex",
      justifyContent: "space-between",
      gap: 14,
      padding: "10px 0",
      borderBottom: "1px solid rgba(255,255,255,0.08)",
      alignItems: "center",
      flexWrap: "wrap",
    },

    loginWrap: {
      maxWidth: 520,
      width: "100%",
      margin: "0 auto",
      paddingTop: 48,
      display: "grid",
      gap: 16,
    },

    brandRow: {
      display: "flex",
      gap: 12,
      alignItems: "center",
      padding: "0 6px",
    },

    brandTitle: { fontSize: 18, fontWeight: 1000, color: THEME.text },
    brandSub: { fontSize: 13, color: THEME.text2 },

    loginCard: {
      background: `linear-gradient(180deg, rgba(255,255,255,0.04), rgba(255,255,255,0.02))`,
      border: `1px solid ${THEME.border}`,
      borderRadius: 18,
      padding: 18,
      boxShadow: THEME.shadow,
    },

    hint: {
      marginTop: 12,
      fontSize: 12,
      color: THEME.muted,
      borderTop: "1px solid rgba(255,255,255,0.08)",
      paddingTop: 12,
    },
  };
}


function getRadixSelectStyles(isMobile) {
  return {
    trigger: {
      width: "100%",
      padding: isMobile ? "9px 10px" : "11px 12px",
      borderRadius: 12,
      border: "1px solid rgba(255,255,255,0.12)",
      background: "rgba(255,255,255,0.04)",
      color: "rgba(255,255,255,0.92)",
      outline: "none",
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      boxSizing: "border-box",
      minHeight: isMobile ? 38 : 44,
      fontSize: isMobile ? 13 : 14,
    },
    content: {
      zIndex: 9999,
      borderRadius: 14,
      border: "1px solid rgba(255,255,255,0.12)",
      background: "rgba(8, 20, 28, 0.98)",
      boxShadow: "0 18px 40px rgba(0,0,0,0.55)",
      overflow: "hidden",
      width: "var(--radix-select-trigger-width)",
      maxWidth: "calc(100vw - 24px)",
    },
    viewport: {
      padding: 6,
      maxHeight: isMobile ? 220 : 280,
      overflowY: "auto",
    },
    item: {
      padding: isMobile ? "8px 8px" : "10px 10px",
      borderRadius: 10,
      color: "rgba(255,255,255,0.88)",
      cursor: "pointer",
      outline: "none",
      fontSize: isMobile ? 13 : 14,
    },
    scrollBtn: {
      padding: 8,
      textAlign: "center",
      background: "rgba(255,255,255,0.03)",
      borderBottom: "1px solid rgba(255,255,255,0.08)",
      color: "rgba(255,255,255,0.7)",
      cursor: "pointer",
    },
  };
}



export default function App() {
  const [user, setUser] = useState(null);
  const [email, setEmail] = useState("");
  const [pass, setPass] = useState("");
  const [contacts, setContacts] = useState([]);
  const [redirisState, setRedirisState] = useState(null);
  const [telefonicaState, setTelefonicaState] = useState(null);
  const [cfgRediris, setCfgRediris] = useState(makeEmptyCfg());
  const [cfgTelefonica, setCfgTelefonica] = useState(makeEmptyCfg());

  const [busyApply, setBusyApply] = useState(false);
  const [busySave, setBusySave] = useState({ rediris: false, telefonica: false });

  const [logs, setLogs] = useState([]);

  const isMobile = useMediaQuery("(max-width: 900px)");
  const styles = useMemo(() => makeStyles(isMobile), [isMobile]);
  const radixSelectStyles = useMemo(() => getRadixSelectStyles(isMobile), [isMobile]);

  useEffect(() => onAuthStateChanged(auth, setUser), []);
  useEffect(() => {
    if (!user) return;
    (async () => {
      const q = query(collection(db, "contacts"), where("active", "==", true));
      const snap = await getDocs(q);
      const list = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
      list.sort((a, b) => String(a.displayName).localeCompare(String(b.displayName)));
      setContacts(list);
    })();
  }, [user]);
  useEffect(() => {
    if (!user) return;
    const unsubscribers = [
      onSnapshot(doc(db, "config", DEVICES.REDIRIS), (s) => {
        if (s.exists()) setCfgRediris(normalizeCfg(s.data()));
      }),
      onSnapshot(doc(db, "config", DEVICES.TELEFONICA), (s) => {
        if (s.exists()) setCfgTelefonica(normalizeCfg(s.data()));
      }),
    ];
    return () => {
      unsubscribers.forEach((unsub) => unsub());
    };
  }, [user]);
  useEffect(() => {
    if (!user) return;
    const unsubscribers = [
      onSnapshot(doc(db, "devices", DEVICES.REDIRIS), (s) =>
        setRedirisState(s.exists() ? s.data() : null)
      ),
      onSnapshot(doc(db, "devices", DEVICES.TELEFONICA), (s) =>
        setTelefonicaState(s.exists() ? s.data() : null)
      ),
    ];
    return () => {
      unsubscribers.forEach((unsub) => unsub());
    };
  }, [user]);
  useEffect(() => {
    if (!user) return;
    const q = query(collection(db, "auditLogs"), orderBy("at", "desc"), limit(10));
    return onSnapshot(q, (snap) => {
      setLogs(snap.docs.map((d) => ({ id: d.id, ...d.data() })));
    });
  }, [user]);
  const optionsN1Rediris = useMemo(
    () => buildOptionsForDevice(contacts, DEVICES.REDIRIS, "N1", true),
    [contacts]
  );
  const optionsN2Rediris = useMemo(
    () => buildOptionsForDevice(contacts, DEVICES.REDIRIS, "N2", false),
    [contacts]
  );

  const optionsN1Telefonica = useMemo(
    () => buildOptionsForDevice(contacts, DEVICES.TELEFONICA, "N1", true),
    [contacts]
  );
  const optionsN2Telefonica = useMemo(
    () => buildOptionsForDevice(contacts, DEVICES.TELEFONICA, "N2", false),
    [contacts]
  );

  const busyRediris = isDeviceBusy(redirisState);
  const busyTelefonica = isDeviceBusy(telefonicaState);
  const busyAny = busyRediris || busyTelefonica;

  async function login(e) {
    e.preventDefault();
    await signInWithEmailAndPassword(auth, email, pass);
  }

  async function acquireLockOrThrow(deviceId, lockId) {
    const ref = doc(db, "devices", deviceId);

    await runTransaction(db, async (tx) => {
      const snap = await tx.get(ref);
      const data = snap.exists() ? snap.data() : {};
      const lock = data?.applyLock;

      const now = Date.now();
      const active = lock?.locked && typeof lock?.expiresAt === "number" && lock.expiresAt > now;
      if (active) {
        const who = lock?.lockedBy || "otro usuario";
        throw new Error(`DISPOSITIVO_OCUPADO:${deviceId}:${who}`);
      }

      tx.set(ref, {
        applyLock: {
          locked: true,
          lockId,
          lockedBy: user.email,
          lockedAt: serverTimestamp(),
          expiresAt: now + LOCK_TTL_MS,
        },
        status: "running",
        resultCode: "LOCKED_BY_WEB",
        resultado: "Aplicando...",
        motivo: "Bloqueo preventivo (web)",
        statusReason: "Bloqueo preventivo (web)",
        lastAt: serverTimestamp(),
      }, { merge: true });
    });
  }

  async function releaseLockIfOwned(deviceId, lockId) {
    const ref = doc(db, "devices", deviceId);
    await runTransaction(db, async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists()) return;
      const lock = snap.data()?.applyLock;
      if (!lock?.locked || lock?.lockId !== lockId || lock?.lockedBy !== user.email) return;
      tx.set(ref, {
        applyLock: {
          locked: false,
          lockId,
          lockedBy: user.email,
          lockedAt: serverTimestamp(),
          expiresAt: 0,
        },
      }, { merge: true });
    });
  }

  async function acquireLocksForBoth(lockId) {
    const acquired = [];
    try {
      for (const deviceId of DEVICE_LIST) {
        await acquireLockOrThrow(deviceId, lockId);
        acquired.push(deviceId);
      }
    } catch (e) {
      await Promise.all(acquired.map((deviceId) => releaseLockIfOwned(deviceId, lockId)));
      throw e;
    }
  }

  async function forceNextTurnBoth() {
    try {
      if (!user) return;
      setBusyApply(true);

      const requestId = makeRequestId();
      const lockId = `web-force-${requestId}`;
      await acquireLocksForBoth(lockId);

      const batch = writeBatch(db);

      for (const deviceId of DEVICE_LIST) {
        batch.set(
          doc(db, "config", deviceId),
          {
            override: {
              forceNextTurn: true,
              forceRequestId: requestId,
              uiTag: "FORZADO",
              uiReason: "Siguiente turno ahora",
              requestedBy: user.email,
              requestedAt: serverTimestamp(),
            },
          },
          { merge: true }
        );
        batch.set(
          doc(db, "commands", deviceId),
          {
            action: "APPLY_NOW",
            requestId: `force-next-${requestId}`,
            requestedBy: user.email,
            requestedAt: serverTimestamp(),
            lockId,
          },
          { merge: true }
        );
      }
      batch.set(doc(collection(db, "auditLogs")), {
        type: "OVERRIDE",
        scope: "AMBOS",
        action: "FORCE_NEXT_TURN",
        actionEs: "Forzar siguiente turno",
        requestId,
        userEmail: user.email,
        at: serverTimestamp(),
      });

      await batch.commit();
    } catch (e) {
      console.error("ERROR forceNextTurnBoth:", e);
      const msg = String(e?.message ?? e);
      if (msg.startsWith("DISPOSITIVO_OCUPADO:")) {
        alert("Hay una aplicación en curso. No puedes pisarla.");
      } else {
        alert("Error al forzar el siguiente turno: " + msg);
      }
    } finally {
      setBusyApply(false);
    }
  }


  async function addAuditLog(entry) {
    await addDoc(collection(db, "auditLogs"), {
      ...entry,
      userEmail: user.email,
      at: serverTimestamp(),
    });
  }

  async function applyNowBoth() {
    try {
      if (!user) return;
      setBusyApply(true);

      const requestId = makeRequestId();
      const lockId = `web-${requestId}`;
      await acquireLocksForBoth(lockId);

      const payload = {
        action: "APPLY_NOW",
        requestId,
        requestedBy: user.email,
        requestedAt: serverTimestamp(),
        lockId,
      };

      await Promise.all([
        setDoc(doc(db, "commands", DEVICES.REDIRIS), payload, { merge: true }),
        setDoc(doc(db, "commands", DEVICES.TELEFONICA), payload, { merge: true }),
      ]);

      await addAuditLog({
        type: "COMANDO",
        action: "APPLY_NOW",
        actionEs: "Aplicar ahora",
        scope: "AMBOS",
        requestId,
      });
    } catch (e) {
      console.error("ERROR applyNowBoth:", e);
      const msg = String(e?.message ?? e);
      if (msg.startsWith("DISPOSITIVO_OCUPADO:")) {
        alert("Hay una aplicación en curso. No puedes pisarla.");
      } else {
        alert("Error al enviar el comando: " + msg);
      }
    } finally {
      setBusyApply(false);
    }
  }

  async function saveDeviceConfig(deviceId, cfg) {
    if (!user) return;
    const safeCfg = cleanCfg(cfg);
    const n2GuardId = toNullableId(safeCfg.n2GuardId);
    if (!n2GuardId) {
      alert("Selecciona una Guardia N2 obligatoria antes de guardar.");
      return;
    }

    try {
      setBusySave((p) => ({ ...p, [deviceId]: true }));

      const mode = safeCfg.mode || MODE.NORMAL;
      const officeMode = mode === MODE.OFICINA;
      const forceHoliday = mode === MODE.FESTIVO;
      const n1Active = mode === MODE.NORMAL;
      const cleanedShifts =
        mode === MODE.OFICINA
          ? { ...safeCfg.shifts, morning: NONE, afternoon: NONE, night: NONE }
          : mode === MODE.FESTIVO
            ? { ...safeCfg.shifts, morning: NONE, intershift: NONE, afternoon: NONE, night: NONE }
            : safeCfg.shifts;

      const intershiftId = toNullableId(cleanedShifts.intershift);

      const payload = {
        mode,
        officeMode,
        forceHoliday,
        n1Active,

        n2GuardId,

        shifts: {
          morning: {
            start: "07:00",
            end: "15:00",
            days: DAYS_LV,
            contactId: toNullableId(cleanedShifts.morning),
          },
          intershift: {
            start: "08:30",
            end: "17:30",
            days: DAYS_LV,
            contactId: intershiftId,
          },
          intershiftLJ: {
            start: "08:30",
            end: "17:30",
            days: ["MON", "TUE", "WED", "THU"],
            contactId: intershiftId,
          },
          intershiftFri: {
            start: "08:00",
            end: "15:00",
            days: ["FRI"],
            contactId: intershiftId,
          },

          afternoon: {
            start: "15:00",
            end: "23:00",
            days: DAYS_LV,
            contactId: toNullableId(cleanedShifts.afternoon),
          },
          night: {
            start: "23:00",
            end: "07:00",
            days: DAYS_DJ_NOCHE,
            contactId: toNullableId(cleanedShifts.night),
          },
        },
        updatedBy: user.email,
        updatedAt: serverTimestamp(),
      };

      await setDoc(doc(db, "config", deviceId), payload, { merge: true });

      await addAuditLog({
        type: "CONFIGURACIÓN_GUARDADA",
        deviceId,
        payload: {
          mode,
          n2GuardId,
          shifts: {
            morning: toNullableId(cleanedShifts.morning),
            intershift: intershiftId,
            afternoon: toNullableId(cleanedShifts.afternoon),
            night: toNullableId(cleanedShifts.night),
          },
        },
      });
    } catch (e) {
      console.error("ERROR saveDeviceConfig:", e);
      alert("Error al guardar configuración: " + (e?.message ?? e));
    } finally {
      setBusySave((p) => ({ ...p, [deviceId]: false }));
    }
  }

  if (!user) {
    return (
      <div style={styles.page}>
        <div style={styles.loginWrap}>
          <div style={styles.brandRow}>
            <div style={styles.brandMark} />
            <div>
              <div style={styles.brandTitle}>Gestor de desvíos</div>
              <div style={styles.brandSub}>Acceso restringido</div>
            </div>
          </div>

          <div style={styles.loginCard}>
            <h2 style={{ margin: 0, color: THEME.text }}>Iniciar sesión</h2>
            <p style={{ marginTop: 8, marginBottom: 18, color: THEME.text2 }}>
              Introduce tus credenciales para acceder a la configuración.
            </p>

            <form onSubmit={login}>
              <label style={styles.label}>Correo</label>
              <input
                style={styles.input}
                placeholder="nombre@empresa.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
              <label style={styles.label}>Contraseña</label>
              <input
                style={styles.input}
                placeholder="••••••••"
                type="password"
                value={pass}
                onChange={(e) => setPass(e.target.value)}
              />
              <button style={styles.primaryBtn} type="submit">
                Entrar
              </button>
            </form>

            <div style={styles.hint}>
              Recomendación: usa Chrome/Edge en PC para mejor experiencia.
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div style={styles.page}>
      <div style={styles.shell}>
        <header style={styles.header}>
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <div style={styles.brandMark} />
            <div>
              <div style={styles.headerTitle}>Desvíos automáticos</div>
              <div style={styles.headerSub}>
                Configuración centralizada · {user.email}
              </div>
            </div>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <button
              style={{ ...styles.primaryBtn, width: "auto", opacity: busyApply || busyAny ? 0.7 : 1 }}
              onClick={forceNextTurnBoth}
              disabled={busyApply || busyAny}
              title={busyAny ? "Hay un proceso de aplicación en curso" : "Fuerza el paso al siguiente turno en ambos móviles y aplica ahora"}
            >
              {busyAny ? "Ocupado (aplicando…)" : "Siguiente turno ahora (ambos)"}
            </button>

            <div
              style={{
                display: "grid",
                gridTemplateColumns: isMobile ? "1fr 1fr" : "auto auto",
                gap: 10,
                alignItems: "center",
              }}
            >
              <button
                style={{
                  ...styles.primaryBtn,
                  width: "100%",
                  opacity: busyApply || busyAny ? 0.7 : 1,
                }}
                onClick={applyNowBoth}
                disabled={busyApply || busyAny}
                title={busyAny ? "Hay un proceso de aplicación en curso" : "Envía el comando"}
              >
                {busyAny ? "Ocupado (aplicando…)" : busyApply ? "Enviando…" : "Aplicar ahora (ambos)"}
              </button>

              <button style={styles.ghostBtn} onClick={() => signOut(auth)}>
                Salir
              </button>
            </div>
          </div>

        </header>

        <div style={styles.gridMain}>
          <ConfigPanel
            title="RedIRIS"
            cfg={cfgRediris}
            setCfg={setCfgRediris}
            optionsN1={optionsN1Rediris}
            optionsN2={optionsN2Rediris}
            onSave={() => saveDeviceConfig("rediris", cfgRediris)}
            saving={busySave.rediris}
            isMobile={isMobile}
            styles={styles}
            radixSelectStyles={radixSelectStyles}
          />

          <ConfigPanel
            title="Telefónica"
            cfg={cfgTelefonica}
            setCfg={setCfgTelefonica}
            optionsN1={optionsN1Telefonica}
            optionsN2={optionsN2Telefonica}
            onSave={() => saveDeviceConfig("telefonica", cfgTelefonica)}
            saving={busySave.telefonica}
            isMobile={isMobile}
            styles={styles}
            radixSelectStyles={radixSelectStyles}
          />

          <div style={styles.sideStack}>
            <section style={styles.sectionCard}>
              <div style={styles.sectionHead}>
                <h3 style={styles.sectionTitle}>Estado de los móviles</h3>
                <span style={styles.sectionHint}></span>
              </div>

              <div style={{ display: "grid", gap: 12 }}>
                <DeviceCard title="RedIRIS" data={redirisState} styles={styles} />
                <DeviceCard title="Telefónica" data={telefonicaState} styles={styles} />
              </div>
            </section>

            <section style={{ ...styles.sectionCard, ...styles.logsCard }}>
              <div style={styles.sectionHead}>
                <h3 style={styles.sectionTitle}>Logs (Últimas 10 acciones)</h3>
                <span style={styles.sectionHint}></span>
              </div>

              {logs.length === 0 ? (
                <div style={{ color: THEME.muted }}>Sin registros todavía.</div>
              ) : (
                <div style={styles.logsWrap}>
                  {logs.map((l) => (
                    <div key={l.id} style={styles.logRow}>
                      <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
                        <span style={badgeForLog(l, styles)}>{l.type ?? "LOG"}</span>
                        <span style={{ color: THEME.text2 }}>
                          {l.deviceId ?? l.scope ?? "-"}
                        </span>
                      </div>

                      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
                        <span style={{ color: THEME.muted, fontSize: 13 }}>
                          {l.userEmail ?? "-"}
                        </span>
                        <span style={{ color: THEME.muted }}>·</span>
                        <span style={{ color: THEME.muted, fontSize: 13 }}>
                          {l.at?.toDate ? l.at.toDate().toLocaleString() : "-"}
                        </span>

                        {(l.action || l.actionEs) ? (
                          <>
                            <span style={{ color: THEME.muted }}>·</span>
                            <span style={{ color: THEME.text2, fontSize: 13 }}>
                              {l.actionEs ?? prettyAction(l.action)}
                            </span>
                          </>
                        ) : null}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </div>
        </div>
      </div>
    </div>
  );
}



function ConfigPanel({ title, cfg, setCfg, optionsN1, optionsN2, onSave, saving, isMobile, styles, radixSelectStyles }) {
  const mode = cfg.mode || MODE.NORMAL;

  const [open, setOpen] = useState(!isMobile);
  useEffect(() => {
    if (!isMobile) setOpen(true);
    if (isMobile) setOpen(false);
  }, [isMobile]);

  function setMode(nextMode) {
    if (nextMode === MODE.OFICINA) {
      setCfg({
        ...cfg,
        mode: MODE.OFICINA,
        shifts: {
          ...cfg.shifts,
          morning: NONE,
          afternoon: NONE,
          night: NONE,
        },
      });
      return;
    }

    if (nextMode === MODE.FESTIVO) {
      setCfg({
        ...cfg,
        mode: MODE.FESTIVO,
        shifts: {
          ...cfg.shifts,
          morning: NONE,
          intershift: NONE,
          afternoon: NONE,
          night: NONE,
        },
      });
      return;
    }

    setCfg({ ...cfg, mode: MODE.NORMAL });
  }

  const isNormal = mode === MODE.NORMAL;
  const isOffice = mode === MODE.OFICINA;
  const isHoliday = mode === MODE.FESTIVO;

  return (
    <div style={styles.card}>
      <div
        onClick={() => isMobile && setOpen((v) => !v)}
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          gap: 12,
          cursor: isMobile ? "pointer" : "default",
          userSelect: "none",
        }}
      >
        <div>
          <div style={styles.cardTitle}>{title}</div>
          <div style={styles.cardSub}>Configuración del móvil corporativo</div>
        </div>

        {isMobile ? (
          <div style={{ color: THEME.text2, fontWeight: 900, fontSize: 16 }}>
            {open ? "▲" : "▼"}
          </div>
        ) : null}
      </div>

      {open ? (
        <>
          <div style={styles.divider} />

          <div style={{ display: "grid", gap: 10 }}>
            <div style={{ color: THEME.text, fontWeight: 800 }}>Modo de operación</div>

            <RadioRow
              label="Normal"
              hint="Turnos N1 + Entreturno + fuera de turno → N2."
              checked={isNormal}
              onChange={() => setMode(MODE.NORMAL)}
              styles={styles}
            />
            <RadioRow
              label="Oficina"
              hint="Solo entreturno; fuera de oficina → N2."
              checked={isOffice}
              onChange={() => setMode(MODE.OFICINA)}
              styles={styles}
            />
            <RadioRow
              label="Festivo"
              hint="Ignora turnos: desvío directo a N2."
              checked={isHoliday}
              onChange={() => setMode(MODE.FESTIVO)}
              styles={styles}
            />
          </div>

          <div style={styles.divider} />

          <div style={styles.formBlock}>
            <Select
              label="Guardia N2 (obligatoria)"
              value={cfg.n2GuardId}
              options={optionsN2}
              onChange={(v) => setCfg({ ...cfg, n2GuardId: v })}
              placeholder="Selecciona guardia N2"
              styles={styles}
              radixSelectStyles={radixSelectStyles}
            />
          </div>

          <div style={styles.divider} />

          <div style={{ display: "grid", gap: 10, opacity: isHoliday ? 0.45 : 1 }}>
            <Select
              label={SHIFT_META.intershift.label}
              value={cfg.shifts.intershift}
              options={optionsN1}
              disabled={isHoliday}
              onChange={(v) => setCfg({ ...cfg, shifts: { ...cfg.shifts, intershift: v } })}
              styles={styles}
              radixSelectStyles={radixSelectStyles}
            />

            <Select
              label={SHIFT_META.morning.label}
              value={cfg.shifts.morning}
              options={optionsN1}
              disabled={!isNormal}
              onChange={(v) => setCfg({ ...cfg, shifts: { ...cfg.shifts, morning: v } })}
              styles={styles}
              radixSelectStyles={radixSelectStyles}
            />

            <Select
              label={SHIFT_META.afternoon.label}
              value={cfg.shifts.afternoon}
              options={optionsN1}
              disabled={!isNormal}
              onChange={(v) => setCfg({ ...cfg, shifts: { ...cfg.shifts, afternoon: v } })}
              styles={styles}
              radixSelectStyles={radixSelectStyles}
            />

            <Select
              label={SHIFT_META.night.label}
              value={cfg.shifts.night}
              options={optionsN1}
              disabled={!isNormal}
              onChange={(v) => setCfg({ ...cfg, shifts: { ...cfg.shifts, night: v } })}
              styles={styles}
              radixSelectStyles={radixSelectStyles}
            />
          </div>

          <button
            style={{
              ...styles.primaryBtn,
              marginTop: 14,
              opacity: saving ? 0.7 : 1,
            }}
            onClick={onSave}
            disabled={saving}
          >
            {saving ? "Guardando…" : "Guardar configuración"}
          </button>
        </>
      ) : null}
    </div>
  );
}

function RadioRow({ label, hint, checked, onChange, styles }) {
  return (
    <label style={{ ...styles.toggleRow, cursor: "pointer" }}>
      <div style={{ display: "grid", gap: 4 }}>
        <span style={{ color: THEME.text, fontWeight: 800 }}>{label}</span>
        <span style={{ color: THEME.muted, fontSize: 13 }}>{hint}</span>
      </div>
      <input
        type="radio"
        checked={checked}
        onChange={onChange}
        style={styles.radio}
      />
    </label>
  );
}

function Select({ label, value, options, onChange, disabled, placeholder = "— Sin asignar —", styles, radixSelectStyles }) {
  return (
    <div style={{ display: "grid", gap: 6, opacity: disabled ? 0.45 : 1 }}>
      <div style={styles.label}>{label}</div>

      <div style={{ pointerEvents: disabled ? "none" : "auto" }}>
        <SelectControl
          value={value}
          onChange={onChange}
          options={options}
          placeholder={placeholder}
          styles={radixSelectStyles}
        />
      </div>
    </div>
  );
}

function DeviceCard({ title, data, styles }) {
  const dateStr = data?.lastAt?.toDate ? data.lastAt.toDate().toLocaleString() : "-";
  const status = data?.status ?? "sin_datos";
  const resultado = data?.resultado ?? data?.lastResult ?? "-";
  const motivo = data?.statusReason ?? data?.motivo ?? data?.lastReason ?? "-";

  const target = data?.lastTargetName ?? data?.lastTargetId ?? "-";
  const turno = data?.turno ?? data?.lastTargetMode ?? "-";

  const detectedApplySource = data?.applySource
    ?? (String(data?.resultCode ?? "").startsWith("AUTO") ? "AUTO" : null);
  const applySourceLabel =
    detectedApplySource === "AUTO"
      ? "Automático"
      : detectedApplySource === "MANUAL"
        ? "Manual"
        : detectedApplySource === "FORCED"
          ? "Forzado"
          : "Desconocido (versión antigua)";

  const nextChangeStr =
    typeof data?.nextChangeAt === "number"
      ? new Date(data.nextChangeAt).toLocaleString()
      : "-";
  const lockBy = data?.applyLock?.lockedBy ?? "-";
  const lockUntil =
    typeof data?.applyLock?.expiresAt === "number" && data.applyLock.expiresAt > 0
      ? new Date(data.applyLock.expiresAt).toLocaleString()
      : "-";

  return (
    <div style={styles.deviceCard}>
      <div style={{ display: "flex", justifyContent: "space-between", gap: 10, alignItems: "center" }}>
        <div style={{ fontWeight: 800, color: THEME.text }}>{title}</div>
        <span style={badgeForStatus(status, styles)}>{prettyStatus(status)}</span>
      </div>

      <div style={styles.kvGrid}>
        <KV k="Resultado" v={resultado} />
        <KV k="Destino" v={target} />
        <KV k="Turno" v={turno} />
        <KV k="Próximo cambio" v={nextChangeStr} />
        <KV k="Lock por" v={lockBy} />
        <KV k="Lock hasta" v={lockUntil} />
        <KV
          k="Motivo"
          v={(
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>
              <span style={badgeForApplySource(detectedApplySource, styles)}>{applySourceLabel}</span>
              <span>{motivo}</span>
            </div>
          )}
        />
        <KV k="Última actualización" v={dateStr} />
      </div>
    </div>
  );
}

function KV({ k, v }) {
  return (
    <div style={{ display: "grid", gap: 4 }}>
      <div style={{ color: THEME.muted, fontSize: 12 }}>{k}</div>
      <div style={{ color: THEME.text2, fontSize: 14 }}>{v}</div>
    </div>
  );
}
