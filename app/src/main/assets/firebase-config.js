// Your Firebase project settings. These values are public identifiers, not passwords:
// what protects your data is the rule in firestore.rules (only you can read your own subjects).
//
// Replace every PASTE... value with the one from Firebase (see SETUP-ACCOUNTS.md).
// Until then the app works normally, without accounts or sync.
window.STUDY_FIREBASE_CONFIG = {
  apiKey: "PASTE_API_KEY",
  authDomain: "PASTE_PROJECT_ID.firebaseapp.com",
  projectId: "PASTE_PROJECT_ID",
  appId: "PASTE_APP_ID",
  storageBucket: "",
  messagingSenderId: ""
};

// The page that handles "Continue with Google" (served by GitHub Pages from this repository).
window.STUDY_AUTH_HELPER_URL = "https://sudo-apexo.github.io/study-colors/auth.html";
