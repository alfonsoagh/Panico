package com.example.panico;

public class Contact {
    public String name;
    public String phone; // número en formato “libre”; lo normalizamos al enviar

    public Contact() {}

    public Contact(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    @Override public String toString() {
        return (name == null || name.isEmpty() ? "Contacto" : name) + " – " + (phone == null ? "" : phone);
    }
}
