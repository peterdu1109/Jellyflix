#!/usr/bin/env bash
# Génère une clé de signature pour les releases et affiche les 4 secrets GitHub à créer.
# Usage : scripts/create-keystore.sh [dossier-de-sortie]
# La clé et le fichier de secrets ne doivent JAMAIS être commités (ils sont ignorés par .gitignore).
set -euo pipefail

OUT="${1:-.}"
JKS="$OUT/jellyflix-release.jks"
[ -e "$JKS" ] && { echo "Erreur : $JKS existe déjà, je ne l'écrase pas." >&2; exit 1; }

PW="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)"
keytool -genkeypair -noprompt -keystore "$JKS" -storetype PKCS12 -alias jellyflix \
  -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PW" -keypass "$PW" \
  -dname "CN=Jellyflix, O=Jellyflix, C=FR"

SECRETS="$OUT/jellyflix-signing-secrets.txt"
umask 077
cat > "$SECRETS" <<TXT
KEYSTORE_PASSWORD=$PW
KEY_PASSWORD=$PW
KEY_ALIAS=jellyflix
KEYSTORE_BASE64=$(base64 -w0 "$JKS")
TXT
echo "Clé créée : $JKS"
echo "Secrets   : $SECRETS  (à copier dans GitHub → Settings → Secrets and variables → Actions)"
echo "Sauvegardez ces deux fichiers : une clé perdue = plus de mises à jour possibles."
