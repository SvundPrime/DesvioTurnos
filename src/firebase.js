import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";

const firebaseConfig = {
  apiKey: "AIzaSyBGDoaAJPBOQFKKMqwSMOf-FxFJLyu-l-c",
  authDomain: "desvio-llamadas.firebaseapp.com",
  projectId: "desvio-llamadas",
  appId: "1:633052656166:web:fb72b072d8658a947bace7",
};

const app = initializeApp(firebaseConfig);

export const auth = getAuth(app);
export const db = getFirestore(app);
