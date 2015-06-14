# SmartImageView
功能强大的图片加载工具，结合多种图片下载工具的优点，尽量避免OOM
，使用的时候只需要 SmartImageView.load(container,url);采用SingleThread下载图片，图片存取的方式如下：
1.去内存中获取
2.本地获取
3.网络获取

本工具发生OOM的可能性极小，能够解决大部分图片下载问题
