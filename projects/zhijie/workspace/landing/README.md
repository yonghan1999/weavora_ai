# 织界落地页

这是一个无构建依赖的 GitHub Pages 静态页面，入口为 `index.html`。

## 本地预览

```powershell
python -m http.server 8080 --directory projects/zhijie/workspace/landing
```

然后打开 `http://localhost:8080`。

## 发布到 GitHub Pages

在 GitHub 仓库设置中将 Pages 的发布源指向包含 `index.html` 的目录即可。页面不依赖 Node、字体 CDN 或图片 CDN。
