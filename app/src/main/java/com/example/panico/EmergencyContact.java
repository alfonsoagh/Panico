package com.example.panico;

public class EmergencyContact {
    public String name;
    public String phoneFormatted;
    public String phoneE164;
    public boolean isPrimary;

    public EmergencyContact() {}
    public EmergencyContact(String name, String phoneFormatted, String phoneE164, boolean isPrimary) {
        this.name = name;
        this.phoneFormatted = phoneFormatted;
        this.phoneE164 = phoneE164;
        this.isPrimary = isPrimary;
    }
}
