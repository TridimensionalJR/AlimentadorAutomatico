package br.com.tridimensional.alimentador_api.modelos;

import jakarta.persistence.*;

import br.com.tridimensional.alimentador_api.modelos.Usuario;

import java.time.Duration;
import java.time.LocalTime;

@Entity
@Table(name = "tb_alimentador")
public class Alimentador {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Integer id;
    private String nome;
    private Integer dosagem;
    private Boolean horarioFixo;
    private LocalTime horario;
    private Duration intervalo;
    private Boolean estado;//Se o alimentador está ou não destativado
    @ManyToOne
    @JoinColumn(name = "usuario_email")
    private Usuario usuario;
}
