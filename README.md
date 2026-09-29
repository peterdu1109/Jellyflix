# Jellyflix

Client Jellyfin **natif** pour **Android** et **Android TV** (un seul APK), écrit en Kotlin / Jetpack Compose.
Objectif : une app fluide, propre, et **personnalisable** (thèmes, plugins) là où le client officiel reste très classique.

## Fonctionnalités

- **Connexion distante** : détection de l'adresse (ajout du schéma/port, préférence https), identifiant + mot de passe, **Quick Connect**, comptes multiples.
- **Accueil** : Reprendre la lecture, À suivre, Favoris, Derniers ajouts par bibliothèque.
- **Bibliothèques** : grille paginée (chargement à la volée), tri (A–Z, ajout, année, note).
- **Détail** : films, séries, saisons, épisodes, similaires, vu / favori.
- **Recherche** avec debounce et annulation des requêtes obsolètes.
- **Lecteur Media3 (ExoPlayer)** :
  - lecture directe quand l'appareil le permet (profil d'appareil construit depuis les décodeurs réels : HEVC, VP9, AV1, AC3/EAC3…), sinon transcodage HLS par le serveur ;
  - repli automatique en transcodage si la lecture directe échoue ;
  - pistes audio / sous-titres (externes, intégrés, ou incrustés pour PGS/VobSub), qualité maximale ;
  - reprise de lecture et **rapport de progression** au serveur ;
  - **Passer l'intro / le générique** via les segments média du serveur (compatible plugin *Intro Skipper*) ;
  - épisode suivant automatique.
- **Android TV** : navigation D-pad complète (anneau de focus, rail latéral, raccourcis télécommande dans le lecteur).

## Thèmes

Réglages → Apparence : mode clair / sombre / système, **6 thèmes** (Jellyfin, Ocean, Forest, Sunset, Rose, Mono), **couleurs dynamiques** Material You, **noir pur AMOLED**, **accent personnalisé**.
Ajouter un thème = ajouter une ligne dans `Palettes` (`ui/theme/Theme.kt`).

## Plugins

Deux notions distinctes :

1. **Plugins client** (`plugin/Plugins.kt`) — extensions de l'app, activables dans Réglages → Plugins. Contrats : `HomeSectionPlugin` (lignes de l'accueil) et `PlayerPlugin` (comportements du lecteur). Fournis : lignes d'accueil, favoris, saut d'intro/générique, épisode suivant automatique.
2. **Plugins serveur** — la liste des plugins installés sur le serveur est affichée dans les réglages (si le compte a les droits) ; les fonctions qui en dépendent (ex. Intro Skipper) sont exploitées par l'app.

## Architecture

```
app/src/main/java/dev/jellyflix
├── data/      SessionManager (auth, comptes), MediaRepository (API), Settings (DataStore), AppContainer (DI)
├── plugin/    contrats + plugins intégrés
├── player/    PlayerViewModel (ExoPlayer, PlaybackInfo, rapports), DeviceProfiles
└── ui/        theme, components, screens, navigation (téléphone : barre du bas / TV : rail)
```

- SDK officiel `org.jellyfin.sdk` (1.6.x, serveurs Jellyfin 10.10+).
- Pas de framework d'injection : un graphe unique (`AppContainer`) créé par l'`Application`.
- État d'écran via `ViewModel` + `StateFlow`, `Load<T>` commun pour chargement / erreur / succès.

## Compiler

Prérequis : JDK 17+, Android SDK (API 35).

```bash
./gradlew :app:assembleDebug     # APK dans app/build/outputs/apk/debug
./gradlew :app:assembleRelease   # R8 activé
```

## Limites connues

- Non testé sur appareil physique dans cet environnement : à valider sur un téléphone et une TV avant diffusion.
- Les jetons de session sont stockés dans le DataStore privé de l'app (non chiffré) ; à migrer vers le Keystore pour durcir.
- Musique, live TV, téléchargements hors-ligne et Chromecast ne sont pas encore couverts.
