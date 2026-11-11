# Aim Pool Pro Overlay — Projet Android Natif

Application d'aide à la visée professionnelle 3-bandes pour 8 Ball Pool.
Fonctionne 100% hors-ligne, sans publicité, avec rendu matériel pass-through et architecture anti-ban sécurisée.

## 🚀 Comment compiler l'APK ?

### Option 1 : Compilation Automatique dans le Cloud (Sans PC via GitHub Actions)
1. Créez un nouveau dépôt sur GitHub (Public ou Privé).
2. Décompressez cette archive et poussez les fichiers sur votre dépôt GitHub :
   ```bash
   git init
   git add .
   git commit -m "Initial commit Aim Pool Pro"
   git branch -M main
   git remote add origin https://github.com/VOTRE_PSEUDO/aim-pool-pro.git
   git push -u origin main
   ```
3. Allez dans l'onglet **Actions** de votre dépôt GitHub.
4. Le workflow `Build Aim Pool Pro APK` se déclenche automatiquement.
5. Au bout de 2 à 3 minutes, téléchargez le fichier `AimPoolPro-Debug-APK.zip` contenant l'APK installable directement sur votre smartphone Android !

### Option 2 : Compilation Locale avec Android Studio
1. Ouvrez Android Studio (version Hedgehog, Iguana, Koala ou ultérieure).
2. Choisissez **Open** et sélectionnez le dossier racine du projet.
3. Laissez Gradle synchroniser les dépendances.
4. Connectez votre smartphone en mode Débogage USB ou lancez un émulateur.
5. Cliquez sur le bouton vert **Run 'app'** ou lancez `./gradlew assembleDebug`.

## 🛡️ Architecture de Sécurité (Anti-Ban & Non-Intrusif)
- **100% Pass-Through** : Utilise le flag `FLAG_NOT_TOUCHABLE` sur la vue de dessin plein écran.
- **Zéro Hook Mémoire** : Ne modifie aucun fichier de 8 Ball Pool, ne lit pas sa RAM.
- **Harmonie avec la Molette Native** : Détecte l'orientation ou permet des poignées discrètes sans masquer la molette du jeu.
