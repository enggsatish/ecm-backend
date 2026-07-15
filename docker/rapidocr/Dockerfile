FROM python:3.11-slim

# OpenCV runtime dependencies (headless — no GUI needed for server OCR)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 libglib2.0-0 libsm6 libxrender1 libxext6 \
    && rm -rf /var/lib/apt/lists/*

# Pin versions for reproducibility and security
RUN pip install --no-cache-dir \
    rapidocr-onnxruntime==1.4.4 \
    rapidocr_api==0.2.0 \
    opencv-python-headless==4.13.0.92

# Run as non-root user
RUN useradd -m -s /bin/bash ocruser
USER ocruser

EXPOSE 9003
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:9003/docs')" || exit 1

CMD ["rapidocr_api", "-ip", "0.0.0.0", "-p", "9003"]
