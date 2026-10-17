// Your Firebase project settings. These values are public identifiers, not passwords:
// what protects your data is the rule in firestore.rules (only you can read your own subjects).
//
// Filled in from the study-colors Firebase project (see SETUP-ACCOUNTS.md).
window.STUDY_FIREBASE_CONFIG = {
  apiKey: "AIzaSyDwjmxlpcHnfXig292UxH9yv72hcwzmHHg",
  authDomain: "study-colors.firebaseapp.com",
  projectId: "study-colors",
  appId: "1:992844163133:web:522af0766e14b322a5e254",
  storageBucket: "study-colors.firebasestorage.app",
  messagingSenderId: "992844163133"
};

// The page that handles "Continue with Google" (served by GitHub Pages from this repository).
window.STUDY_AUTH_HELPER_URL = "https://sudo-apexo.github.io/study-colors/auth.html";

// Android only: the "Web client ID" of this Firebase project. With it, "Continue with Google" opens the
// phone's own account picker instead of the browser. Find it in Firebase: Authentication > Sign-in method >
// Google > Web SDK configuration. It looks like 123456789-abc123.apps.googleusercontent.com
window.STUDY_GOOGLE_WEB_CLIENT_ID = "";
