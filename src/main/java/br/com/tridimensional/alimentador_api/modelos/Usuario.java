package br.com.tridimensional.alimentador_api.modelos;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Usuario {
    @Id
    private String email;
    private String nomedeUsuario;
}
