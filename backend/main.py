import os
import shutil
import tempfile
from typing import Optional
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from openai import OpenAI
from anthropic import Anthropic
from dotenv import load_dotenv

# Load env variables from .env file
load_dotenv()

app = FastAPI(title="Wispr Flow Clone API", version="1.0.0")

# Enable CORS for local testing from mobile device emulators/clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class TranscribeResponse(BaseModel):
    raw_text: str
    polished_text: str
    app_name: Optional[str] = None

# System prompt from documentation, with dynamic formatting instructions
BASE_SYSTEM_PROMPT = """Du bist das Text-Optimierungs-Modul einer professionellen Diktier-App. 
Deine Aufgabe ist es, das rohe, gesprochene Transkript in perfekten, geschriebenen Text zu verwandeln.

REGELN:
1. Entferne absolut alle Füllwörter (ähm, ah, wie gesagt, öh, sozusagen, ja).
2. Bereinige Selbstkorrekturen intelligent. (Beispiel: "Wir sehen uns um 5... ah nee, um 6" wird zu "Wir sehen uns um 6 Uhr.")
3. Achte extrem präzise auf korrekte deutsche Rechtschreibung, Grammatik und Zeichensetzung (insbesondere Kommasetzung).
4. Füge sinnvolle Satzzeichen (Punkte, Kommas, Fragezeichen, Ausrufezeichen) basierend auf dem Sinn und Ton des Satzes ein.
5. Falls der Nutzer explizite Formatierungsanweisungen gibt (z.B. "Neue Zeile", "Absatz", "Mach daraus eine Liste: Punkt eins..."), wende diese an.
6. Verändere niemals den inhaltlichen Kern oder den Sinn der Aussage.
7. Füge keinerlei Metatext, Erklärungen oder KI-Floskeln hinzu (Antworte NIEMALS mit "Hier ist dein bereinigter Text:"). Gib AUSSCHLIESSLICH den finalen, optimierten Text zurück.
"""

def get_app_specific_prompt(app_name: Optional[str]) -> str:
    if not app_name:
        return BASE_SYSTEM_PROMPT
    
    app_lower = app_name.lower()
    
    # Slack, MS Teams, etc.
    if any(x in app_lower for x in ["slack", "teams", "discord", "chat"]):
        style_instruction = "\nKONTEXT: Der Nutzer schreibt in einem Business-Chat (z. B. Slack). Schreibe prägnant, direkt und im passenden Chat-Stil."
    # WhatsApp, Signal, iMessage, etc.
    elif any(x in app_lower for x in ["whatsapp", "signal", "telegram", "message", "imessage"]):
        style_instruction = "\nKONTEXT: Der Nutzer schreibt eine private Chat-Nachricht (z. B. WhatsApp). Schreibe in einem lockeren, freundlichen und natürlichen Tonfall."
    # Mail apps, Outlook, Notes, text editors
    elif any(x in app_lower for x in ["mail", "outlook", "gmail", "notes", "word", "textedit", "pages"]):
        style_instruction = """
KONTEXT: Der Nutzer schreibt eine E-Mail oder ein Dokument. 
Verwende einen formellen, höflichen und gut strukturierten Briefstil mit passenden Absätzen.
WICHTIG FÜR E-MAILS:
- Formatiere Briefanreden (z. B. "Sehr geehrter Herr X,", "Hallo Frau Y,") immer in einer eigenen Zeile, gefolgt von einem Komma und einer Leerzeile (Doppelabsatz) vor dem eigentlichen Nachrichtentext.
- Beginne den Text nach der Anrede kleingeschrieben (außer es ist ein Nomen), wie im Deutschen nach einem Komma üblich.
- Formatiere Grußformeln am Ende (z. B. "Mit freundlichen Grüßen,", "Beste Grüße") ebenfalls in einer eigenen Zeile mit Absatz davor.
"""
    else:
        style_instruction = f"\nKONTEXT: Der Nutzer schreibt in der App '{app_name}'. Passe den Schreibstil und die Formatierung subtil an diesen Kontext an."
        
    return BASE_SYSTEM_PROMPT + style_instruction

@app.get("/")
def health_check():
    return {
        "status": "healthy",
        "providers": {
            "groq": "GROQ_API_KEY" in os.environ,
            "openai": "OPENAI_API_KEY" in os.environ,
            "anthropic": "ANTHROPIC_API_KEY" in os.environ,
            "nvidia": "NVIDIA_API_KEY" in os.environ
        }
    }

@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe_audio(
    file: UploadFile = File(...),
    app_name: Optional[str] = Form(None)
):
    # Determine which clients are available
    groq_api_key = os.environ.get("GROQ_API_KEY")
    openai_api_key = os.environ.get("OPENAI_API_KEY")
    anthropic_api_key = os.environ.get("ANTHROPIC_API_KEY")
    nvidia_api_key = os.environ.get("NVIDIA_API_KEY")

    # Console debug logging
    print("--- Transcribe Request ---")
    print(f"Detected Keys: GROQ_API_KEY={'set' if groq_api_key else 'MISSING'}, NVIDIA_API_KEY={'set' if nvidia_api_key else 'MISSING'}, OPENAI_API_KEY={'set' if openai_api_key else 'MISSING'}, ANTHROPIC_API_KEY={'set' if anthropic_api_key else 'MISSING'}")

    if not groq_api_key and not openai_api_key and not nvidia_api_key:
        raise HTTPException(
            status_code=500,
            detail="Config Error: None of the keys GROQ_API_KEY, OPENAI_API_KEY, or NVIDIA_API_KEY are configured in the environment."
        )

    # Save uploaded file to a temporary file with its original extension
    suffix = os.path.splitext(file.filename)[1] if file.filename else ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_audio:
        shutil.copyfileobj(file.file, temp_audio)
        temp_audio_path = temp_audio.name

    try:
        raw_text = ""
        
        # 1. Transcribe Audio
        # Try Groq first (very fast)
        if groq_api_key and not raw_text:
            try:
                print("Attempting Groq transcription...")
                groq_client = OpenAI(
                    api_key=groq_api_key,
                    base_url="https://api.groq.com/openai/v1"
                )
                with open(temp_audio_path, "rb") as audio_file:
                    translation = groq_client.audio.transcriptions.create(
                        model="whisper-large-v3",
                        file=audio_file
                    )
                    raw_text = translation.text
                    print("Groq transcription successful!")
            except Exception as e:
                print(f"Groq transcription failed: {e}. Falling back...")

        # Try NVIDIA NIM Whisper
        if nvidia_api_key and not raw_text:
            try:
                print("Attempting NVIDIA transcription...")
                nvidia_client = OpenAI(
                    api_key=nvidia_api_key,
                    base_url="https://integrate.api.nvidia.com/v1"
                )
                model_name = os.environ.get("NVIDIA_WHISPER_MODEL", "openai/whisper-large-v3")
                with open(temp_audio_path, "rb") as audio_file:
                    translation = nvidia_client.audio.transcriptions.create(
                        model=model_name,
                        file=audio_file
                    )
                    raw_text = translation.text
                    print("NVIDIA transcription successful!")
            except Exception as e:
                print(f"NVIDIA transcription failed: {e}. Falling back...")

        # Try OpenAI Whisper
        if openai_api_key and not raw_text:
            try:
                print("Attempting OpenAI transcription...")
                openai_client = OpenAI(api_key=openai_api_key)
                with open(temp_audio_path, "rb") as audio_file:
                    translation = openai_client.audio.transcriptions.create(
                        model="whisper-1",
                        file=audio_file
                    )
                    raw_text = translation.text
                    print("OpenAI transcription successful!")
            except Exception as e:
                print(f"OpenAI transcription failed: {e}. Falling back...")

        if not raw_text:
            raise HTTPException(status_code=500, detail="Transcription resulted in empty text or failed on all providers.")

        # 2. Polish raw text using LLM
        polished_text = ""
        system_prompt = get_app_specific_prompt(app_name)

        # Try Anthropic Claude if available
        if anthropic_api_key:
            try:
                anthropic_client = Anthropic(api_key=anthropic_api_key)
                message = anthropic_client.messages.create(
                    model="claude-3-5-haiku-20241022",
                    max_tokens=1024,
                    system=system_prompt,
                    messages=[
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{raw_text}"}
                    ]
                )
                polished_text = message.content[0].text.strip()
            except Exception as e:
                print(f"Anthropic polishing failed: {e}. Falling back...")

        # Try OpenAI (GPT-4o-mini)
        if not polished_text and openai_api_key:
            try:
                openai_client = OpenAI(api_key=openai_api_key)
                completion = openai_client.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{raw_text}"}
                    ],
                    temperature=0.3
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"OpenAI polishing failed: {e}. Falling back...")

        # Try NVIDIA NIM LLM
        if not polished_text and nvidia_api_key:
            try:
                nvidia_client = OpenAI(
                    api_key=nvidia_api_key,
                    base_url="https://integrate.api.nvidia.com/v1"
                )
                model_name = os.environ.get("NVIDIA_LLM_MODEL", "meta/llama-3.3-70b-instruct")
                
                # Check for DeepSeek specific options
                extra_args = {}
                if "deepseek" in model_name.lower():
                    extra_args["extra_body"] = {"chat_template_kwargs": {"thinking": False}}
                
                completion = nvidia_client.chat.completions.create(
                    model=model_name,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{raw_text}"}
                    ],
                    temperature=0.3,
                    **extra_args
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"NVIDIA polishing failed: {e}. Falling back...")

        # Try Groq Llama3
        if not polished_text and groq_api_key:
            try:
                groq_client = OpenAI(
                    api_key=groq_api_key,
                    base_url="https://api.groq.com/openai/v1"
                )
                completion = groq_client.chat.completions.create(
                    model="llama-3.3-70b-versatile",
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{raw_text}"}
                    ],
                    temperature=0.3
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"Groq polishing failed: {e}. Falling back...")

        if not polished_text:
            raise HTTPException(status_code=500, detail="Polishing step failed to return text on all providers.")

        return TranscribeResponse(
            raw_text=raw_text,
            polished_text=polished_text,
            app_name=app_name
        )

    finally:
        # Clean up temporary audio file
        try:
            os.unlink(temp_audio_path)
        except Exception:
            pass

if __name__ == "__main__":
    import uvicorn
    # Get port from environment or default to 8000
    port = int(os.environ.get("PORT", 8000))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=True)
