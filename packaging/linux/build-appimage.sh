#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.0.0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_DIR="${ROOT_DIR}/composeApp/build/compose/binaries/main/app/PingMap"
if [ ! -d "${DIST_DIR}" ]; then
  DIST_DIR="${ROOT_DIR}/composeApp/build/compose/binaries/main/app/pingmap"
fi
WORK_DIR="${ROOT_DIR}/build/appimage"
APPDIR="${WORK_DIR}/PingMap.AppDir"
TAR_STAGE="${WORK_DIR}/tarstage/PingMap"
OUT_APPIMAGE="${ROOT_DIR}/build/PingMap-${VERSION}-x86_64.AppImage"
OUT_TARBALL="${ROOT_DIR}/build/PingMap-${VERSION}-linux-x86_64.tar.gz"

if [ ! -d "${DIST_DIR}" ]; then
  echo "ERROR: Compose distributable not found under composeApp/build/compose/binaries/main/app/"
  exit 1
fi

rm -rf "${APPDIR}" "${WORK_DIR}/tarstage"
mkdir -p "${APPDIR}/usr/share/metainfo" "${APPDIR}/usr/share/icons/hicolor/256x256/apps" "${TAR_STAGE}"

cp -a "${DIST_DIR}/." "${APPDIR}/usr/"
cp -a "${DIST_DIR}/." "${TAR_STAGE}/"

BIN_NAME="PingMap"
if [ ! -x "${APPDIR}/usr/bin/${BIN_NAME}" ]; then
  BIN_NAME="pingmap"
fi

cp "${ROOT_DIR}/packaging/linux/pingmap.desktop" "${APPDIR}/pingmap.desktop"
sed -i "s|^Exec=.*|Exec=${BIN_NAME}|" "${APPDIR}/pingmap.desktop"
cp "${ROOT_DIR}/packaging/icons/pingmap.png" "${APPDIR}/pingmap.png"
cp "${ROOT_DIR}/packaging/icons/pingmap.png" "${APPDIR}/usr/share/icons/hicolor/256x256/apps/pingmap.png"
cp "${ROOT_DIR}/packaging/linux/io.github.ilievi187.pingmap.metainfo.xml" \
   "${APPDIR}/usr/share/metainfo/io.github.ilievi187.pingmap.metainfo.xml"
ln -sf "pingmap.png" "${APPDIR}/.DirIcon"

cat > "${APPDIR}/AppRun" << EOF
#!/usr/bin/env bash
HERE="\$(dirname "\$(readlink -f "\${0}")")"
export _JAVA_AWT_WM_NONREPARENTING=1
export _JAVA_OPTIONS="\${_JAVA_OPTIONS:-} -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true"

if [ -n "\${DISPLAY:-}" ] && [[ "\${DISPLAY}" =~ ^:([0-9]+)\$ ]]; then
  dnum="\${BASH_REMATCH[1]}"
  if [ ! -S "/tmp/.X11-unix/X\${dnum}" ]; then
    for sock in "/tmp/.X11-unix/X\${dnum}_" /tmp/.X11-unix/X*; do
      if [ -S "\${sock}" ]; then
        export DISPLAY="\${sock}"
        break
      fi
    done
  fi
fi

exec "\${HERE}/usr/bin/${BIN_NAME}" "\$@"
EOF
chmod +x "${APPDIR}/AppRun"

cp "${ROOT_DIR}/packaging/linux/pingmap.desktop" "${TAR_STAGE}/pingmap.desktop"
cp "${ROOT_DIR}/packaging/icons/pingmap.png" "${TAR_STAGE}/pingmap.png"
cp "${ROOT_DIR}/packaging/linux/io.github.ilievi187.pingmap.metainfo.xml" "${TAR_STAGE}/io.github.ilievi187.pingmap.metainfo.xml"
cp "${ROOT_DIR}/LICENSE" "${TAR_STAGE}/LICENSE"
cp "${APPDIR}/AppRun" "${TAR_STAGE}/launch-pingmap.sh"
sed -i 's|${HERE}/usr/bin/|${HERE}/bin/|g' "${TAR_STAGE}/launch-pingmap.sh"
chmod +x "${TAR_STAGE}/launch-pingmap.sh"

tar -C "${WORK_DIR}/tarstage" -czf "${OUT_TARBALL}" "PingMap"

APPIMAGETOOL="${WORK_DIR}/appimagetool-x86_64.AppImage"
if [ ! -x "${APPIMAGETOOL}" ]; then
  curl -fsSL -o "${APPIMAGETOOL}" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "${APPIMAGETOOL}"
fi

ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "${APPIMAGETOOL}" "${APPDIR}" "${OUT_APPIMAGE}"
chmod +x "${OUT_APPIMAGE}"
echo "Built AppImage: ${OUT_APPIMAGE}"
