export const avatarField = '<div class="field"><label for="field-avatar">群头像（PNG 或 JPEG，最多 2 MiB）</label><input id="field-avatar" name="avatar" type="file" accept="image/png,image/jpeg"></div>';

export async function readGroupAvatar(file, previous = null) {
  if (!file?.name) return previous;
  if (!file.size || file.size > 2 * 1024 * 1024 || !['image/png', 'image/jpeg'].includes(file.type)) throw Error('GROUP_AVATAR_INVALID');
  const bytes = new Uint8Array(await file.slice(0, 8).arrayBuffer());
  const signature = file.type === 'image/png' ? [137, 80, 78, 71, 13, 10, 26, 10] : [255, 216, 255];
  if (!signature.every((value, index) => bytes[index] === value)) throw Error('GROUP_AVATAR_INVALID');
  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
    const side = Math.min(bitmap.width, bitmap.height);
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    canvas.getContext('2d').drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, 64, 64);
    return canvas.toDataURL('image/png');
  } catch { throw Error('GROUP_AVATAR_INVALID'); }
  finally { bitmap?.close(); }
}

export function groupAvatarImage(value) {
  return typeof value === 'string' && value.length <= 32768 && /^data:image\/png;base64,[A-Za-z0-9+/]+=*$/.test(value)
    ? `<img class="group-avatar-image" alt="" src="${value}">` : '';
}
